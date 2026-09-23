package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.Times;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService {
    private final Db db;
    private final BackgroundJobService jobs;
    private final AgentRunService agentRuns;
    private final AiClient ai;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public ActivityService(Db db, BackgroundJobService jobs, AgentRunService agentRuns, AiClient ai, QqService qq,
                           ObjectMapper mapper, AppProperties properties) {
        this.db = db;
        this.jobs = jobs;
        this.agentRuns = agentRuns;
        this.ai = ai;
        this.qq = qq;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> queue(AccountPrincipal account, String text, boolean publishToQq,
                                     String classId, JsonNode workflow) {
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO activities (id, user_id, title, raw_input, status, created_at) VALUES (?, ?, ?, ?, 'queued', ?)",
                id, account.username(), text.substring(0, Math.min(20, text.length())), text, now);
        String group = null;
        String organizationId = null;
        if (classId != null && !classId.isBlank()) {
            Map<String, Object> classroom = db.one("SELECT c.qq_group_openid, co.organization_id FROM classes c JOIN class_organizations co ON co.class_id=c.id WHERE c.id=?", classId)
                    .orElseThrow(() -> ApiException.notFound("班级不存在"));
            db.update("INSERT INTO activity_classes (activity_id, class_id, organization_id) VALUES (?, ?, ?)", id, classId, classroom.get("organization_id"));
            organizationId = String.valueOf(classroom.get("organization_id"));
            if (workflow != null) db.update("INSERT INTO activity_workflows (activity_id, config_json) VALUES (?, ?)", id, workflow.toString());
            if (publishToQq && classroom.get("qq_group_openid") != null) {
                group = String.valueOf(classroom.get("qq_group_openid"));
                db.update("INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at) VALUES (?, ?, ?, ?)", id, account.username(), group, now);
            }
        } else if (publishToQq) {
            group = qq.attachActivity(account.username(), id);
        }
        Map<String, Object> job = jobs.enqueue("activity", id);
        agentRuns.create(String.valueOf(job.get("id")), "activity", id, organizationId, account.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity_id", id); result.put("run_id", job.get("id")); result.put("status", job.get("status")); result.put("qq_group_attached", group != null);
        return result;
    }

    public List<Map<String, Object>> list(AccountPrincipal account) {
        if (account.isManager()) return db.list("SELECT DISTINCT a.*, ac.class_id FROM activities a JOIN activity_classes ac ON ac.activity_id=a.id " +
                "JOIN class_organizations co ON co.class_id=ac.class_id JOIN organization_members om ON om.organization_id=co.organization_id " +
                "WHERE om.account_id=? AND om.role IN ('owner','manager') ORDER BY a.created_at DESC", account.id());
        return db.list("SELECT DISTINCT a.* FROM activities a JOIN tasks t ON t.activity_id=a.id JOIN activity_task_assignees ata ON ata.task_id=t.id " +
                "WHERE ata.account_id=? ORDER BY a.created_at DESC", account.id());
    }

    public void requireOwner(String activityId, String username) {
        Map<String, Object> row = db.one("SELECT user_id FROM activities WHERE id=?", activityId)
                .orElseThrow(() -> ApiException.notFound("活动不存在: " + activityId));
        if (!username.equals(row.get("user_id"))) throw ApiException.forbidden("只能操作自己创建的活动");
    }

    public Map<String, Object> detail(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> activity = new LinkedHashMap<>(db.required("SELECT * FROM activities WHERE id=?", activityId));
        JsonNode plan = null;
        try { if (activity.get("plan_json") != null) plan = mapper.readTree(String.valueOf(activity.get("plan_json"))); } catch (Exception ignored) {}
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", activity); result.put("plan", plan);
        result.put("tasks", db.list("SELECT * FROM tasks WHERE activity_id=?", activityId));
        result.put("forms", db.list("SELECT * FROM forms WHERE activity_id=?", activityId));
        result.put("reminders", db.list("SELECT * FROM reminders WHERE activity_id=?", activityId));
        result.put("steps", db.list("SELECT * FROM steps WHERE activity_id=? ORDER BY id", activityId));
        result.put("deliveries", db.list("SELECT * FROM delivery_events WHERE activity_id=? ORDER BY created_at DESC", activityId));
        return result;
    }

    public Map<String, Object> run(String activityId) {
        Map<String, Object> activity = db.one("SELECT * FROM activities WHERE id=?", activityId)
                .orElseThrow(() -> ApiException.notFound("活动不存在: " + activityId));
        db.update("UPDATE activities SET status='running' WHERE id=?", activityId);
        agentRuns.start(activityId);
        try {
            Map<String, Object> result = orchestrate(activity);
            db.update("UPDATE activities SET status='ready' WHERE id=?", activityId);
            agentRuns.complete(activityId, result);
            return result;
        } catch (RuntimeException exception) {
            db.update("UPDATE activities SET status='failed' WHERE id=?", activityId);
            agentRuns.fail(activityId, exception);
            throw exception;
        }
    }

    @Transactional
    protected Map<String, Object> orchestrate(Map<String, Object> activity) {
        String activityId = String.valueOf(activity.get("id"));
        JsonNode workflow = workflow(activityId);
        String classId = db.one("SELECT class_id FROM activity_classes WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("class_id"))).orElse(null);
        List<String> assignees = classId == null ? List.of() : db.list("SELECT a.display_name FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                "WHERE cm.class_id=? AND a.status='active' ORDER BY a.student_no, a.display_name", classId).stream()
                .map(row -> String.valueOf(row.get("display_name"))).toList();
        agentRuns.phase(activityId, "planning");
        JsonNode plan = activity.get("plan_json") == null
                ? ai.plan(String.valueOf(activity.get("raw_input")), assignees)
                : parse(String.valueOf(activity.get("plan_json")));
        String title = plan.path("title").asText(String.valueOf(activity.get("title")));
        db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?", title, plan.toString(), activityId);
        agentRuns.planned(activityId, plan);
        logStep(activityId, "planning", "generate_plan", "done", title, "plan:" + activityId,
                Map.of("raw_input", activity.get("raw_input")), plan, null);
        agentRuns.phase(activityId, "execution");

        String formId = null;
        if (flag(workflow, "create_form", true) && requested(plan, "create_form")) {
            Map<String, Object> old = idempotent("form:" + activityId, "create_form");
            if (old == null) {
                formId = Ids.shortId();
                ArrayNode fields = mapper.createArrayNode();
                fields.add(mapper.createObjectNode().put("name", "name").put("label", "姓名"));
                fields.add(mapper.createObjectNode().put("name", "contact").put("label", "联系方式"));
                if (plan.path("form_fields").isArray()) plan.path("form_fields").forEach(field -> {
                    String name = field.path("name").asText();
                    if (!"name".equals(name) && !"contact".equals(name)) fields.add(field);
                });
                db.update("INSERT INTO forms (id, activity_id, title, fields_json, created_at) VALUES (?, ?, ?, ?, ?)",
                        formId, activityId, title + "报名表", fields.toString(), LocalDateTime.now().toString());
                saveIdempotent("form:" + activityId, "create_form", Map.of("form_id", formId));
            } else formId = String.valueOf(old.get("form_id"));
            logStep(activityId, "execution", "create_form", "done", formId, "form:" + activityId,
                    null, Map.of("form_id", formId), null);
        }

        if (flag(workflow, "assign_tasks", true) && requested(plan, "assign_tasks") && plan.path("tasks").isArray()) {
            int assignedCount = 0;
            for (JsonNode task : plan.path("tasks")) {
                String taskTitle = task.path("title").asText();
                String assignee = task.path("assignee").asText();
                if (classId != null && !assignees.contains(assignee)) continue;
                if (db.one("SELECT 1 FROM tasks WHERE activity_id=? AND title=? AND assignee=?", activityId, taskTitle, assignee).isEmpty()) {
                    String taskId = Ids.shortId();
                    db.update("INSERT INTO tasks (id, activity_id, title, assignee, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
                            taskId, activityId, taskTitle, assignee, LocalDateTime.now().toString());
                    if (classId != null) db.one("SELECT a.id FROM class_members cm JOIN accounts a ON a.id=cm.account_id WHERE cm.class_id=? AND a.display_name=? LIMIT 1", classId, assignee)
                            .ifPresent(member -> db.update("INSERT INTO activity_task_assignees (task_id, account_id) VALUES (?, ?)", taskId, member.get("id")));
                }
                String content = "【" + title + "】你被分配了任务「" + taskTitle + "」，活动时间 " + plan.path("event_time").asText() + "，请及时完成。";
                String key = "msg:" + activityId + ":" + Integer.toHexString((assignee + content).hashCode());
                if (idempotent(key, "send_message") == null) {
                    db.update("INSERT INTO messages (activity_id, recipient, content, created_at) VALUES (?, ?, ?, ?)", activityId, assignee, content, LocalDateTime.now().toString());
                    saveIdempotent(key, "send_message", Map.of("status", "sent"));
                }
                assignedCount += 1;
            }
            logStep(activityId, "execution", "assign_tasks", "done", assignedCount + " 项任务",
                    "tasks:" + activityId, plan.path("tasks"),
                    Map.of("requested_count", plan.path("tasks").size(), "assigned_count", assignedCount), null);
        }

        if (flag(workflow, "create_calendar", true) && requested(plan, "create_calendar")) {
            String key = "cal:" + activityId;
            if (idempotent(key, "create_calendar_event") == null) {
                String ics = calendar(title, plan.path("event_time").asText(), plan.path("description").asText());
                saveIdempotent(key, "create_calendar_event", Map.of("title", title, "event_time", plan.path("event_time").asText(), "ics", ics));
            }
            logStep(activityId, "execution", "create_calendar_event", "done", plan.path("event_time").asText(),
                    key, null, Map.of("event_time", plan.path("event_time").asText()), null);
        }

        String reminderId = null;
        if (flag(workflow, "schedule_reminder", true) && requested(plan, "schedule_reminder") && !plan.path("event_time").asText().isBlank()) {
            Instant remindAt = Times.instant(plan.path("event_time").asText(), properties.timezone())
                    .minusSeconds(plan.path("remind_minutes_before").asLong(60) * 60);
            String time = LocalDateTime.ofInstant(remindAt, java.time.ZoneId.of(properties.timezone())).toString();
            Map<String, Object> old = db.one("SELECT * FROM reminders WHERE activity_id=? AND remind_at=?", activityId, time).orElse(null);
            if (old == null) {
                reminderId = Ids.shortId();
                List<String> materials = new ArrayList<>();
                plan.path("materials").forEach(node -> materials.add(node.asText()));
                db.update("INSERT INTO reminders (id, activity_id, message, remind_at, status, created_at) VALUES (?, ?, ?, ?, 'scheduled', ?)",
                        reminderId, activityId, "活动「" + title + "」将于 " + plan.path("event_time").asText() + " 开始，物料：" + String.join("、", materials) + "，请各负责人就位！",
                        time, LocalDateTime.now().toString());
            } else reminderId = String.valueOf(old.get("id"));
            logStep(activityId, "execution", "schedule_reminder", "done", reminderId,
                    "reminder:" + activityId, null, Map.of("reminder_id", reminderId), null);
        }

        agentRuns.phase(activityId, "delivery");
        String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
        if (group != null && flag(workflow, "publish_message", true) && requested(plan, "publish_message")) {
            StringBuilder announcement = new StringBuilder("【发布】").append(title).append("\n\n时间：")
                    .append(plan.path("event_time").asText()).append("\n\n").append(plan.path("description").asText());
            if (formId != null) announcement.append("\n\n填写链接：").append(properties.publicBaseUrl().replaceAll("/$", "")).append("/forms/").append(formId);
            sendActivityQq(activityId, group, announcement.toString(), "announcement", "qq:announcement:" + activityId);
        }
        Map<String, Object> deliverySummary = new LinkedHashMap<>();
        deliverySummary.put("qq_group_attached", group != null);
        deliverySummary.put("published", group != null && flag(workflow, "publish_message", true));
        logStep(activityId, "delivery", "complete_delivery", "done",
                group == null ? "无需外部群投递，业务结果已就绪" : "交付阶段完成",
                "delivery:" + activityId, null, deliverySummary, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity_id", activityId); result.put("plan", plan); result.put("form_id", formId); result.put("reminder_id", reminderId);
        return result;
    }

    public Map<String, Object> registration(String formId, String name, String contact, Map<String, Object> extra) {
        if (db.one("SELECT 1 FROM forms WHERE id=?", formId).isEmpty()) throw ApiException.notFound("问卷不存在: " + formId);
        long id = db.insertReturningId("INSERT INTO registrations (form_id, name, contact, extra_json, created_at) VALUES (:formId, :name, :contact, CAST(:extra AS text), :createdAt)",
                Map.of("formId", formId, "name", name, "contact", contact, "extra", json(extra == null ? Map.of() : extra), "createdAt", LocalDateTime.now().toString()));
        return Map.of("registration_id", id, "form_id", formId);
    }

    public Map<String, Object> publicForm(String formId) {
        Map<String, Object> row = db.one("SELECT f.id, f.title, f.fields_json, a.title AS activity_title, a.plan_json FROM forms f JOIN activities a ON a.id=f.activity_id WHERE f.id=?", formId)
                .orElseThrow(() -> ApiException.notFound("报名表不存在"));
        JsonNode plan = parse(String.valueOf(row.get("plan_json")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("title", row.get("title")); result.put("activity_title", row.get("activity_title"));
        result.put("event_time", plan.path("event_time").asText(null)); result.put("description", plan.path("description").asText(""));
        result.put("fields", parse(String.valueOf(row.get("fields_json"))));
        return result;
    }

    public Map<String, Object> formStats(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> form = db.one("SELECT * FROM forms WHERE activity_id=?", activityId).orElseThrow(() -> ApiException.notFound("该活动还没有报名问卷"));
        List<Map<String, Object>> rows = db.list("SELECT name, contact, extra_json, created_at FROM registrations WHERE form_id=? ORDER BY id", form.get("id"));
        List<Map<String, Object>> registrations = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("extra", parse(String.valueOf(item.remove("extra_json"))));
            return item;
        }).toList();
        return Map.of("form_id", form.get("id"), "title", form.get("title"), "count", registrations.size(), "registrations", registrations);
    }

    public Map<String, Object> recap(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> activity = db.required("SELECT * FROM activities WHERE id=?", activityId);
        Map<String, Object> stats = db.one("SELECT 1 FROM forms WHERE activity_id=?", activityId).isPresent() ? formStats(activityId, username) : Map.of("count", 0, "registrations", List.of());
        String taskSummary = db.list("SELECT title, assignee, status FROM tasks WHERE activity_id=?", activityId).stream()
                .map(task -> task.get("title") + "(" + task.get("assignee") + ")-" + task.get("status"))
                .reduce((a, b) -> a + "；" + b).orElse("无任务");
        String organizationId = db.one("SELECT organization_id FROM activity_classes WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("organization_id"))).orElse("");
        String recap = ai.recap(String.valueOf(activity.get("title")), stats, taskSummary, organizationId);
        db.update("UPDATE activities SET status='finished' WHERE id=?", activityId);
        return Map.of("activity_id", activityId, "recap", recap, "stats", stats);
    }

    public Map<String, Object> cancelReminder(String reminderId, String username) {
        Map<String, Object> row = db.one("SELECT activity_id FROM reminders WHERE id=?", reminderId).orElseThrow(() -> ApiException.notFound("提醒不存在: " + reminderId));
        requireOwner(String.valueOf(row.get("activity_id")), username);
        db.update("UPDATE reminders SET status='cancelled' WHERE id=?", reminderId);
        return Map.of("id", reminderId, "status", "cancelled");
    }

    public Map<String, Object> updateTask(String taskId, String status, AccountPrincipal account) {
        Map<String, Object> task = db.one("SELECT * FROM tasks WHERE id=?", taskId).orElseThrow(() -> ApiException.notFound("任务不存在: " + taskId));
        if (account.isManager()) requireOwner(String.valueOf(task.get("activity_id")), account.username());
        else if (db.one("SELECT 1 FROM activity_task_assignees WHERE task_id=? AND account_id=?", taskId, account.id()).isEmpty())
            throw ApiException.forbidden("该活动任务未分配给你");
        db.update("UPDATE tasks SET status=? WHERE id=?", status, taskId);
        return db.required("SELECT * FROM tasks WHERE id=?", taskId);
    }

    public String calendar(String activityId, String username) {
        requireOwner(activityId, username);
        Map<String, Object> result = idempotent("cal:" + activityId, "create_calendar_event");
        if (result == null) throw ApiException.notFound("该活动还没有日历事件");
        return String.valueOf(result.get("ics"));
    }

    public Map<String, Object> sendManual(String activityId, String username, String content, String requestId) {
        requireOwner(activityId, username);
        String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElseThrow(() -> ApiException.notFound("该活动没有绑定 QQ 群"));
        return sendActivityQq(activityId, group, content, "manual", "manual:" + activityId + ":" + requestId);
    }

    private Map<String, Object> sendActivityQq(String activityId, String group, String content, String kind, String key) {
        Map<String, Object> cached = idempotent(key, "send_qq_group_message");
        if (cached != null) return cached;
        String deliveryId = Ids.shortId();
        String now = LocalDateTime.now().toString();
        try {
            Map<String, Object> sent = qq.sendGroup(group, content, key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("delivery_id", deliveryId); result.put("external_id", sent.get("id")); result.put("recipient", group); result.put("status", "sent");
            db.update("INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, external_id, created_at, completed_at) VALUES (?, ?, ?, 'qq_group', ?, ?, 'sent', ?, ?, ?)",
                    deliveryId, activityId, kind, group, content, sent.get("id"), now, now);
            saveIdempotent(key, "send_qq_group_message", result);
            logStep(activityId, "delivery", "publish_qq", "done", "活动消息已发布到 QQ 群",
                    key, null, result, null);
            return result;
        } catch (RuntimeException exception) {
            db.update("INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, error, created_at, completed_at) VALUES (?, ?, ?, 'qq_group', ?, ?, 'failed', ?, ?, ?)",
                    deliveryId, activityId, kind, group, content, exception.getMessage(), now, now);
            logStep(activityId, "delivery", "publish_qq", "failed", exception.getMessage(),
                    key, null, null, exception.getMessage());
            throw exception;
        }
    }

    private JsonNode workflow(String activityId) {
        return db.one("SELECT config_json FROM activity_workflows WHERE activity_id=?", activityId)
                .map(row -> parse(String.valueOf(row.get("config_json")))).orElseGet(() -> {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("create_form", true).put("assign_tasks", true).put("create_calendar", true)
                            .put("schedule_reminder", true).put("publish_message", true);
                    return node;
                });
    }

    private boolean flag(JsonNode workflow, String key, boolean fallback) {
        return workflow.has(key) ? workflow.path(key).asBoolean() : fallback;
    }

    private void logStep(String activityId, String phase, String step, String status, String detail,
                         String idempotencyKey, Object input, Object output, String error) {
        String shortDetail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500));
        Map<String, Object> old = idempotencyKey == null ? null : db.one(
                "SELECT id, attempts FROM steps WHERE activity_id=? AND idempotency_key=?",
                activityId, idempotencyKey).orElse(null);
        if (old == null) {
            db.update("INSERT INTO steps (activity_id, step, status, detail, created_at, phase, idempotency_key, input_json, output_json, error, attempts) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    activityId, step, status, shortDetail, LocalDateTime.now().toString(), phase, idempotencyKey,
                    input == null ? null : json(input), output == null ? null : json(output), error);
        } else {
            db.update("UPDATE steps SET status=?, detail=?, phase=?, input_json=COALESCE(?, input_json), " +
                            "output_json=COALESCE(?, output_json), error=?, attempts=attempts+1 WHERE id=?",
                    status, shortDetail, phase, input == null ? null : json(input), output == null ? null : json(output),
                    error, old.get("id"));
        }
    }

    private boolean requested(JsonNode plan, String tool) {
        JsonNode intents = plan.path("tool_intents");
        if (!intents.isArray() || intents.isEmpty()) return true;
        for (JsonNode intent : intents) if (tool.equals(intent.path("tool").asText())) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> idempotent(String key, String tool) {
        return db.one("SELECT result_json FROM idempotency_keys WHERE key=? AND tool_name=?", key, tool)
                .map(row -> {
                    try { return mapper.readValue(String.valueOf(row.get("result_json")), Map.class); }
                    catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
                }).orElse(null);
    }

    private void saveIdempotent(String key, String tool, Map<String, Object> result) {
        db.update("INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (key) DO NOTHING",
                key, tool, json(result), LocalDateTime.now().toString());
    }

    private String calendar(String title, String eventTime, String description) {
        return String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Sailor-Moon//Activity Assistant//CN",
                "BEGIN:VEVENT", "UID:" + UUID.randomUUID().toString().replace("-", "") + "@sailor-moon",
                "DTSTAMP:" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()),
                "DTSTART:" + Times.ics(eventTime, properties.timezone()), "SUMMARY:" + Times.escapeIcs(title),
                "DESCRIPTION:" + Times.escapeIcs(description), "END:VEVENT", "END:VCALENDAR", "");
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value == null || "null".equals(value) ? "{}" : value); }
        catch (JsonProcessingException exception) { return mapper.createObjectNode(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}

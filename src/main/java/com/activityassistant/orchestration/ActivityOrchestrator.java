package com.activityassistant.orchestration;

import com.activityassistant.config.AppProperties;
import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.service.AgentRunService;
import com.activityassistant.service.QqService;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.Times;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the fixed activity workflow from planning through delivery.
 *
 * <p>This class deliberately has no broad transaction. The workflow calls AI and QQ services,
 * so wrapping the whole method would hold a database transaction across network I/O. Step-level
 * consistency is handled by {@link ExecutionJournal} and idempotency keys.</p>
 */
@Service
public class ActivityOrchestrator {
    private final Db db;
    private final AgentRunService agentRuns;
    private final AiClient ai;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ExecutionJournal journal;

    public ActivityOrchestrator(Db db, AgentRunService agentRuns, AiClient ai, QqService qq,
                                ObjectMapper mapper, AppProperties properties, ExecutionJournal journal) {
        this.db = db;
        this.agentRuns = agentRuns;
        this.ai = ai;
        this.qq = qq;
        this.mapper = mapper;
        this.properties = properties;
        this.journal = journal;
    }

    public Map<String, Object> run(String activityId) {
        // 学习点: run 负责一次执行的边界：加载活动、进入 running、成功收尾或失败回滚状态。
        Map<String, Object> activity = db.one("SELECT * FROM activities WHERE id=?", activityId)
                .orElseThrow(() -> ApiException.notFound("活动不存在: " + activityId));
        db.update("UPDATE activities SET status='running' WHERE id=?", activityId);
        agentRuns.start(activityId);
        try {
            Map<String, Object> result = orchestrate(activity);
            // 学习点: activities 的成功状态叫 ready，agent_runs 的成功状态叫 succeeded，两者不要混淆。
            db.update("UPDATE activities SET status='ready' WHERE id=?", activityId);
            agentRuns.complete(activityId, result);
            return result;
        } catch (RuntimeException exception) {
            // 学习点: 编排器先把状态写成失败，再抛出异常；是否重试由上层 JobScheduler 决定。
            db.update("UPDATE activities SET status='failed' WHERE id=?", activityId);
            agentRuns.fail(activityId, exception);
            throw exception;
        }
    }

    private Map<String, Object> orchestrate(Map<String, Object> activity) {
        String activityId = String.valueOf(activity.get("id"));
        // 学习点: 先准备 Flow 所需上下文：流程开关、班级、可分配成员。
        JsonNode workflow = workflow(activityId);
        String classId = db.one("SELECT class_id FROM activity_classes WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("class_id"))).orElse(null);
        List<String> assignees = classId == null ? List.of() : db.list(
                        "SELECT a.display_name FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                                "WHERE cm.class_id=? AND a.status='active' ORDER BY a.student_no, a.display_name",
                        classId).stream()
                .map(row -> String.valueOf(row.get("display_name"))).toList();

        agentRuns.phase(activityId, "planning");
        // 学习点: plan_json 已存在时直接复用，这是重跑不再调用 AI 的第一层原因。
        String rawInput = String.valueOf(activity.get("raw_input"));
        String planningInput = planningInput(activityId, rawInput);
        JsonNode plan = activity.get("plan_json") == null
                ? ai.plan(planningInput, assignees)
                : parse(String.valueOf(activity.get("plan_json")));
        String title = plan.path("title").asText(String.valueOf(activity.get("title")));
        db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?", title, plan.toString(), activityId);
        agentRuns.planned(activityId, plan);
        journal.record(activityId, "planning", "generate_plan", "done", title, "plan:" + activityId,
                Map.of("raw_input", planningInput, "available_assignees", assignees), plan, null);

        Flow flow = new Flow(activityId, workflow, classId, assignees, plan, title);
        agentRuns.phase(activityId, "execution");
        // 学习点: 下面四行就是固定业务顺序；时间、下发和失败边界都围绕这条主链展开。
        String formId = createForm(flow);
        assignTasks(flow);
        createCalendar(flow);
        String reminderId = scheduleReminder(flow);

        agentRuns.phase(activityId, "delivery");
        return deliver(flow, formId, reminderId);
    }

    private String createForm(Flow flow) {
        if (!flag(flow.workflow(), "create_form", true) || !requested(flow.plan(), "create_form")) return null;
        // 学习点: form:活动ID 是稳定幂等键，用来识别“同一个活动创建表单”这个逻辑操作。
        String key = "form:" + flow.activityId();
        Map<String, Object> old = journal.find(key, "create_form");
        String formId;
        if (old == null) {
            formId = Ids.shortId();
            ArrayNode fields = mapper.createArrayNode();
            fields.add(mapper.createObjectNode().put("name", "name").put("label", "姓名"));
            fields.add(mapper.createObjectNode().put("name", "contact").put("label", "联系方式"));
            if (flow.plan().path("form_fields").isArray()) flow.plan().path("form_fields").forEach(field -> {
                String name = field.path("name").asText();
                if (!"name".equals(name) && !"contact".equals(name)) fields.add(field);
            });
            db.update("INSERT INTO forms (id, activity_id, title, fields_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    formId, flow.activityId(), flow.title() + "报名表", fields.toString(), LocalDateTime.now().toString());
            journal.save(key, "create_form", Map.of("form_id", formId));
        } else {
            formId = String.valueOf(old.get("form_id"));
        }
        journal.record(flow.activityId(), "execution", "create_form", "done", formId, key,
                Map.of("title", flow.title() + "报名表", "form_fields", flow.plan().path("form_fields")),
                Map.of("form_id", formId), null);
        return formId;
    }

    private void assignTasks(Flow flow) {
        if (!flag(flow.workflow(), "assign_tasks", true)
                || !requested(flow.plan(), "assign_tasks")
                || !flow.plan().path("tasks").isArray()) return;
        int assignedCount = 0;
        for (JsonNode task : flow.plan().path("tasks")) {
            String taskTitle = task.path("title").asText();
            String assignee = task.path("assignee").asText();
            if (flow.classId() != null && !flow.assignees().contains(assignee)) continue;
            // 学习点: 任务没有独立幂等表，因此直接查业务表是否已有“同活动、同标题、同负责人”的记录。
            if (db.one("SELECT 1 FROM tasks WHERE activity_id=? AND title=? AND assignee=?",
                    flow.activityId(), taskTitle, assignee).isEmpty()) {
                String taskId = Ids.shortId();
                db.update("INSERT INTO tasks (id, activity_id, title, assignee, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
                        taskId, flow.activityId(), taskTitle, assignee, LocalDateTime.now().toString());
                if (flow.classId() != null) {
                    db.one("SELECT a.id FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                                    "WHERE cm.class_id=? AND a.display_name=? LIMIT 1", flow.classId(), assignee)
                            .ifPresent(member -> db.update("INSERT INTO activity_task_assignees (task_id, account_id) VALUES (?, ?)",
                                    taskId, member.get("id")));
                }
            }
            String content = "【" + flow.title() + "】你被分配了任务「" + taskTitle + "」，活动时间 "
                    + flow.plan().path("event_time").asText() + "，请及时完成。";
            String key = "msg:" + flow.activityId() + ":" + Integer.toHexString((assignee + content).hashCode());
            if (journal.find(key, "send_message") == null) {
                db.update("INSERT INTO messages (activity_id, recipient, content, created_at) VALUES (?, ?, ?, ?)",
                        flow.activityId(), assignee, content, LocalDateTime.now().toString());
                journal.save(key, "send_message", Map.of("status", "sent"));
            }
            assignedCount += 1;
        }
        journal.record(flow.activityId(), "execution", "assign_tasks", "done", assignedCount + " 项任务",
                "tasks:" + flow.activityId(), flow.plan().path("tasks"),
                Map.of("requested_count", flow.plan().path("tasks").size(), "assigned_count", assignedCount), null);
    }

    private void createCalendar(Flow flow) {
        if (!flag(flow.workflow(), "create_calendar", true) || !requested(flow.plan(), "create_calendar")) return;
        // 学习点: 日历结果也按活动 ID 缓存，重跑时复用同一份 ICS，而不是重新生成。
        String key = "cal:" + flow.activityId();
        String eventTime = flow.plan().path("event_time").asText();
        String description = flow.plan().path("description").asText();
        if (journal.find(key, "create_calendar_event") == null) {
            String ics = calendar(flow.title(), eventTime, description);
            journal.save(key, "create_calendar_event", Map.of("title", flow.title(), "event_time", eventTime, "ics", ics));
        }
        journal.record(flow.activityId(), "execution", "create_calendar_event", "done", eventTime, key,
                Map.of("title", flow.title(), "event_time", eventTime, "description", description),
                Map.of("event_time", eventTime), null);
    }

    private String scheduleReminder(Flow flow) {
        String eventTime = flow.plan().path("event_time").asText();
        if (!flag(flow.workflow(), "schedule_reminder", true)
                || !requested(flow.plan(), "schedule_reminder")
                || eventTime.isBlank()) return null;

        long remindMinutesBefore = flow.plan().path("remind_minutes_before").asLong(60);
        // 学习点: remind_at 是“活动开始时间减去提前量”后的绝对提醒时间。
        Instant remindAt = Times.instant(eventTime, properties.timezone()).minusSeconds(remindMinutesBefore * 60);
        String time = LocalDateTime.ofInstant(remindAt, java.time.ZoneId.of(properties.timezone())).toString();
        // 学习点: 同一活动、同一提醒时间只复用一条提醒；数据库唯一约束还会再做一层兜底。
        Map<String, Object> old = db.one("SELECT * FROM reminders WHERE activity_id=? AND remind_at=?",
                flow.activityId(), time).orElse(null);
        String reminderId;
        if (old == null) {
            reminderId = Ids.shortId();
            List<String> materials = new ArrayList<>();
            flow.plan().path("materials").forEach(node -> materials.add(node.asText()));
            db.update("INSERT INTO reminders (id, activity_id, message, remind_at, status, created_at) VALUES (?, ?, ?, ?, 'scheduled', ?)",
                    reminderId, flow.activityId(), "活动「" + flow.title() + "」将于 " + eventTime + " 开始，物料："
                            + String.join("、", materials) + "，请各负责人就位！", time, LocalDateTime.now().toString());
        } else {
            reminderId = String.valueOf(old.get("id"));
        }
        journal.record(flow.activityId(), "execution", "schedule_reminder", "done", reminderId,
                "reminder:" + flow.activityId(),
                Map.of("event_time", eventTime, "remind_at", time, "remind_minutes_before", remindMinutesBefore),
                Map.of("reminder_id", reminderId), null);
        return reminderId;
    }

    private Map<String, Object> deliver(Flow flow, String formId, String reminderId) {
        // 学习点: delivery 阶段负责外部投递；没有 QQ 群时仍要记录业务结果已经就绪。
        String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", flow.activityId())
                .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
        boolean publishRequested = flag(flow.workflow(), "publish_message", true)
                && requested(flow.plan(), "publish_message");
        if (group != null && publishRequested) {
            StringBuilder announcement = new StringBuilder("【发布】").append(flow.title()).append("\n\n时间：")
                    .append(flow.plan().path("event_time").asText()).append("\n\n")
                    .append(flow.plan().path("description").asText());
            if (formId != null) {
                announcement.append("\n\n填写链接：").append(properties.publicBaseUrl().replaceAll("/$", ""))
                        .append("/forms/").append(formId);
            }
            sendGroupMessage(flow.activityId(), group, announcement.toString(), "announcement",
                    "qq:announcement:" + flow.activityId());
        }

        Map<String, Object> deliverySummary = new LinkedHashMap<>();
        deliverySummary.put("qq_group_attached", group != null);
        deliverySummary.put("published", group != null && flag(flow.workflow(), "publish_message", true));
        journal.record(flow.activityId(), "delivery", "complete_delivery", "done",
                group == null ? "无需外部群投递，业务结果已就绪" : "交付阶段完成",
                "delivery:" + flow.activityId(),
                Map.of("group_attached", group != null, "form_id", formId == null ? "" : formId),
                deliverySummary, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity_id", flow.activityId());
        result.put("plan", flow.plan());
        result.put("form_id", formId);
        result.put("reminder_id", reminderId);
        return result;
    }

    public Map<String, Object> sendGroupMessage(String activityId, String group, String content, String kind, String key) {
        // 学习点: 外部副作用之前先查幂等结果；命中就说明这条消息以前已经成功发过。
        Map<String, Object> cached = journal.find(key, "send_qq_group_message");
        if (cached != null) return cached;
        String deliveryId = Ids.shortId();
        String now = LocalDateTime.now().toString();
        try {
            // 学习点: QQ 和本地 delivery_events 无法组成一个数据库事务，因此用幂等键降低重复发送风险。
            Map<String, Object> sent = qq.sendGroup(group, content, key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("delivery_id", deliveryId);
            result.put("external_id", sent.get("id"));
            result.put("recipient", group);
            result.put("status", "sent");
            db.update("INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, external_id, created_at, completed_at) " +
                            "VALUES (?, ?, ?, 'qq_group', ?, ?, 'sent', ?, ?, ?)",
                    deliveryId, activityId, kind, group, content, sent.get("id"), now, now);
            journal.save(key, "send_qq_group_message", result);
            journal.record(activityId, "delivery", "publish_qq", "done", "活动消息已发布到 QQ 群",
                    key, null, result, null);
            return result;
        } catch (RuntimeException exception) {
            db.update("INSERT INTO delivery_events (id, activity_id, kind, channel, target, content, status, error, created_at, completed_at) " +
                            "VALUES (?, ?, ?, 'qq_group', ?, ?, 'failed', ?, ?, ?)",
                    deliveryId, activityId, kind, group, content, exception.getMessage(), now, now);
            journal.record(activityId, "delivery", "publish_qq", "failed", exception.getMessage(),
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

    private String planningInput(String activityId, String rawInput) {
        // 学习点: 恢复执行时把 input_json 合并进规划文本，但已完成 plan_json 时仍然优先复用旧方案。
        Map<String, Object> run = agentRuns.byRef("activity", activityId);
        if (run == null || run.get("input_json") == null) return rawInput;
        JsonNode input = parse(String.valueOf(run.get("input_json")));
        if (!input.isObject() || input.isEmpty()) return rawInput;
        return rawInput + "\n用户补充信息: " + input;
    }

    private boolean flag(JsonNode workflow, String key, boolean fallback) {
        return workflow.has(key) ? workflow.path(key).asBoolean() : fallback;
    }

    private boolean requested(JsonNode plan, String tool) {
        JsonNode intents = plan.path("tool_intents");
        if (!intents.isArray() || intents.isEmpty()) return true;
        for (JsonNode intent : intents) if (tool.equals(intent.path("tool").asText())) return true;
        return false;
    }

    private String calendar(String title, String eventTime, String description) {
        return String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Sailor-Moon//Activity Assistant//CN",
                "BEGIN:VEVENT", "UID:" + UUID.randomUUID().toString().replace("-", "") + "@sailor-moon",
                "DTSTAMP:" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()),
                "DTSTART:" + Times.ics(eventTime, properties.timezone()), "SUMMARY:" + Times.escapeIcs(title),
                "DESCRIPTION:" + Times.escapeIcs(description), "END:VEVENT", "END:VCALENDAR", "");
    }

    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value == null || "null".equals(value) ? "{}" : value);
        } catch (JsonProcessingException exception) {
            return mapper.createObjectNode();
        }
    }

    private record Flow(String activityId, JsonNode workflow, String classId, List<String> assignees,
                        JsonNode plan, String title) {
    }
}

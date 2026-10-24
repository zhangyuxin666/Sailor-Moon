package com.activityassistant.service;

import com.activityassistant.integration.AiClient;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.orchestration.ExecutionJournal;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.Times;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ActivityService {
    private final Db db;
    private final BackgroundJobService jobs;
    private final AgentRunService agentRuns;
    private final AiClient ai;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final ActivityOrchestrator orchestrator;
    private final ExecutionJournal journal;
    private final RunLifecycleService lifecycle;

    public ActivityService(Db db, BackgroundJobService jobs, AgentRunService agentRuns, AiClient ai, QqService qq,
                           ObjectMapper mapper, ActivityOrchestrator orchestrator, ExecutionJournal journal,
                           RunLifecycleService lifecycle) {
        this.db = db;
        this.jobs = jobs;
        this.agentRuns = agentRuns;
        this.ai = ai;
        this.qq = qq;
        this.mapper = mapper;
        this.orchestrator = orchestrator;
        this.journal = journal;
        this.lifecycle = lifecycle;
    }

    // 学习点: 这个事务只覆盖入队前的数据库写入，不会把后续 AI、QQ 等后台流程包进来。
    @Transactional
    public Map<String, Object> queue(AccountPrincipal account, String text, boolean publishToQq,
                                     String classId, JsonNode workflow) {
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        // 学习点: 先把活动写成 queued，用户马上能拿到 ID，而不是等完整流程执行完。
        db.update("INSERT INTO activities (id, user_id, title, raw_input, status, created_at) VALUES (?, ?, ?, ?, 'queued', ?)",
                id, account.username(), text.substring(0, Math.min(20, text.length())), text, now);
        String group = null;
        String organizationId = null;
        // 学习点: 有班级时保存班级、流程配置和 QQ 群绑定；这些是后续编排需要的上下文。
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
        // 学习点: 真正交给后台的是 background_jobs 中的任务，不是在这里直接执行活动流程。
        Map<String, Object> job = jobs.enqueue("activity", id);
        // 学习点: run_id 复用 job_id，用同一个编号关联任务执行状态和工作流状态。
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
        Map<String, Object> result = journal.find("cal:" + activityId, "create_calendar_event");
        if (result == null) throw ApiException.notFound("该活动还没有日历事件");
        return String.valueOf(result.get("ics"));
    }

    public Map<String, Object> sendManual(String activityId, String username, String content, String requestId) {
        requireOwner(activityId, username);
        String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", activityId)
                .map(row -> String.valueOf(row.get("group_openid"))).orElseThrow(() -> ApiException.notFound("该活动没有绑定 QQ 群"));
        return orchestrator.sendGroupMessage(activityId, group, content, "manual", "manual:" + activityId + ":" + requestId);
    }

    public Map<String, Object> saveRunInput(String runId, String username, JsonNode input) {
        // 学习点: 权限检查放在业务门面，具体等待态校验和落库交给 RunLifecycleService。
        requireOwnedRun(runId, username);
        return lifecycle.saveInput(runId, input);
    }

    public Map<String, Object> resumeRun(String runId, String username) {
        // 学习点: 用户只能恢复自己活动对应的运行，不能只凭 runId 越权操作。
        requireOwnedRun(runId, username);
        return lifecycle.resume(runId);
    }

    private Map<String, Object> requireOwnedRun(String runId, String username) {
        Map<String, Object> run = agentRuns.byId(runId);
        if (run == null) throw ApiException.notFound("运行记录不存在: " + runId);
        if (!"activity".equals(run.get("kind"))) throw ApiException.forbidden("不能操作当前类型的运行记录");
        requireOwner(String.valueOf(run.get("ref_id")), username);
        return run;
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

package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.integration.AiClient;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentDraftService {
    private final Db db;
    private final ClassroomService classroom;
    private final ActivityService activities;
    private final AiClient ai;
    private final AuditService audits;
    private final QqService qq;
    private final ObjectMapper mapper;
    private final RunLifecycleService lifecycle;

    public AgentDraftService(Db db, ClassroomService classroom, ActivityService activities, AiClient ai,
                             AuditService audits, QqService qq, ObjectMapper mapper,
                             RunLifecycleService lifecycle) {
        this.db = db;
        this.classroom = classroom;
        this.activities = activities;
        this.ai = ai;
        this.audits = audits;
        this.qq = qq;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Map<String, Object> create(AccountPrincipal account, String classId, String rawInput) {
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以使用发布 Agent");
        Map<String, Object> classInfo = classroom.requireClassManager(classId, account);
        List<Map<String, Object>> members = db.list(
                "SELECT a.id AS account_id, a.display_name FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                "WHERE cm.class_id=? AND a.status='active' ORDER BY a.student_no, a.id", classId);
        String context = "班级：" + classInfo.get("name") + "；成员数：" + members.size() + "；成员：" + json(members);
        String organizationId = String.valueOf(classInfo.get("organization_id"));
        JsonNode analyzed = ai.analyzeWorkRequest(rawInput, context, organizationId);
        ConfirmedPlans.validateDraft(analyzed);
        ObjectNode draft = analyzed.deepCopy();
        if ("activity".equals(draft.path("intent_type").asText())) {
            // Planning occurs before review; publishing never silently regenerates this snapshot.
            JsonNode generated = ai.plan(rawInput, members, organizationId);
            draft.set("execution_plan", generated.deepCopy());
            if (!draft.path("form_fields").isArray() || draft.path("form_fields").isEmpty())
                draft.set("form_fields", generated.path("form_fields").deepCopy());
        }
        if ("activity".equals(draft.path("intent_type").asText())
                || "survey".equals(draft.path("intent_type").asText())) {
            draft.set("form_fields", ConfirmedPlans.fields(draft.path("form_fields"), mapper));
            draft.set("execution_plan", ConfirmedPlans.fromDraft(draft, mapper));
        }
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO agent_drafts (id, organization_id, class_id, creator_id, raw_input, intent_type, complexity, draft_json, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?)", id, organizationId, classId, account.id(), rawInput,
                draft.path("intent_type").asText(), draft.path("complexity").asText(), draft.toString(), now, now);
        audits.write("agent.draft_create", account.id(), organizationId, "agent_draft", id,
                Map.of("intent_type", draft.path("intent_type").asText(), "complexity", draft.path("complexity").asText()));
        return get(account, id);
    }

    public Map<String, Object> get(AccountPrincipal account, String id) {
        Map<String, Object> row = db.one("SELECT d.*, c.name AS class_name FROM agent_drafts d JOIN classes c ON c.id=d.class_id " +
                "JOIN organization_members om ON om.organization_id=d.organization_id WHERE d.id=? AND om.account_id=?", id, account.id())
                .orElseThrow(() -> ApiException.notFound("草案不存在"));
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("draft", parse(String.valueOf(result.remove("draft_json"))));
        return result;
    }

    public List<Map<String, Object>> list(AccountPrincipal account) {
        List<Map<String, Object>> rows = db.list("SELECT d.id, d.intent_type, d.complexity, d.status, d.result_type, d.result_id, d.created_at, d.updated_at, " +
                "c.name AS class_name, d.draft_json FROM agent_drafts d JOIN classes c ON c.id=d.class_id JOIN organization_members om ON om.organization_id=d.organization_id " +
                "WHERE om.account_id=? ORDER BY d.created_at DESC LIMIT 100", account.id());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            JsonNode data = parse(String.valueOf(item.remove("draft_json")));
            item.put("title", data.path("title").asText());
            item.put("summary", data.path("summary").asText());
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> update(AccountPrincipal account, String id, JsonNode draft) {
        db.one("SELECT id FROM agent_drafts WHERE id=? FOR UPDATE", id)
                .orElseThrow(() -> ApiException.notFound("草案不存在"));
        Map<String, Object> current = get(account, id);
        classroom.requireClassManager(String.valueOf(current.get("class_id")), account);
        if (!"draft".equals(current.get("status"))) throw ApiException.badRequest("只有未发布草案可以修改");
        ConfirmedPlans.validateDraft(draft);
        ObjectNode reviewed = draft.deepCopy();
        JsonNode original=(JsonNode)current.get("draft");
        reviewed.remove("generation");if(original.path("generation").isObject())reviewed.set("generation",original.path("generation").deepCopy());
        if(reviewed.path("execution_plan").isObject()) {
            ObjectNode execution=(ObjectNode)reviewed.path("execution_plan");execution.remove(List.of("generation","analysis_generation"));
            for(String key:List.of("generation","analysis_generation"))if(original.path("execution_plan").path(key).isObject())execution.set(key,original.path("execution_plan").path(key).deepCopy());
        }
        if ("activity".equals(reviewed.path("intent_type").asText())
                || "survey".equals(reviewed.path("intent_type").asText())) {
            if (reviewed.path("workflow").path("assign_tasks").asBoolean(false)
                    && !reviewed.path("execution_plan").isObject())
                throw ApiException.badRequest("旧草案缺少已确认的任务方案，请重新生成后确认");
            reviewed.set("form_fields", ConfirmedPlans.fields(reviewed.path("form_fields"), mapper));
            reviewed.set("execution_plan", ConfirmedPlans.fromDraft(reviewed, mapper));
        }
        db.update("UPDATE agent_drafts SET intent_type=?, complexity=?, draft_json=?, updated_at=? WHERE id=?",
                reviewed.path("intent_type").asText(), reviewed.path("complexity").asText(), reviewed.toString(), LocalDateTime.now().toString(), id);
        audits.write("agent.draft_update", account.id(), String.valueOf(current.get("organization_id")), "agent_draft", id, null);
        return get(account, id);
    }

    @Transactional
    public Map<String, Object> publish(AccountPrincipal account, String id) {
        // Serialize updates/publication, and publish the reviewed plan in the same transaction as queueing.
        db.one("SELECT id FROM agent_drafts WHERE id=? FOR UPDATE", id)
                .orElseThrow(() -> ApiException.notFound("草案不存在"));
        Map<String, Object> current = get(account, id);
        classroom.requireClassManager(String.valueOf(current.get("class_id")), account);
        if ("published".equals(current.get("status"))) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "published");
            response.put("result_type", current.get("result_type"));
            response.put("result_id", current.get("result_id"));
            response.put("idempotent", true);
            addRunState(response, current.get("result_type"), current.get("result_id"));
            return response;
        }
        if (!"draft".equals(current.get("status"))) throw ApiException.badRequest("草案正在发布，请勿重复操作");
        if (db.update("UPDATE agent_drafts SET status='publishing', updated_at=? WHERE id=? AND status='draft'", LocalDateTime.now().toString(), id) == 0)
            throw ApiException.badRequest("草案正在发布，请勿重复操作");
        JsonNode draft = (JsonNode) current.get("draft");
        ConfirmedPlans.validateDraft(draft);
        try {
            PublishOutcome result = execute(account, current, draft);
            db.update("UPDATE agent_drafts SET status='published', result_type=?, result_id=?, updated_at=? WHERE id=?",
                    result.type(), result.id(), LocalDateTime.now().toString(), id);
            audits.write("agent.draft_publish", account.id(), String.valueOf(current.get("organization_id")), result.type(), result.id(), Map.of("draft_id", id));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "published");
            response.put("result_type", result.type());
            response.put("result_id", result.id());
            if (result.runId() != null) response.put("run_id", result.runId());
            if (result.runStatus() != null) response.put("run_status", result.runStatus());
            if (!result.missingInformation().isEmpty()) response.put("missing_information", result.missingInformation());
            return response;
        } catch (RuntimeException exception) {
            db.update("UPDATE agent_drafts SET status='draft', updated_at=? WHERE id=?", LocalDateTime.now().toString(), id);
            throw exception;
        }
    }

    private PublishOutcome execute(AccountPrincipal account, Map<String, Object> current, JsonNode draft) {
        String intent = draft.path("intent_type").asText();
        String classId = String.valueOf(current.get("class_id"));
        if ("assignment".equals(intent)) {
            if (draft.path("deadline").asText().isBlank()) throw ApiException.badRequest("发布作业前必须确认截止时间");
            Map<String, Object> todo = classroom.createTodo(account, classId, new ApiModels.TodoCreateRequest(
                    draft.path("title").asText(), draft.path("description").asText(draft.path("summary").asText()),
                    draft.path("deadline").asText(), "homework"));
            return new PublishOutcome("todo", String.valueOf(todo.get("id")), null, "queued", List.of());
        }
        if ("activity".equals(intent) || "survey".equals(intent)) {
            if (draft.path("workflow").path("assign_tasks").asBoolean(false)
                    && !draft.path("execution_plan").isObject())
                throw ApiException.badRequest("旧草案缺少已确认的任务方案，请重新生成后确认");
            ObjectNode confirmed = ConfirmedPlans.fromDraft(draft, mapper);
            validateAssignees(classId, confirmed);
            String text = activityText(draft);
            Map<String, Object> queued = activities.queue(account, text, draft.path("workflow").path("publish_message").asBoolean(true),
                    classId, draft.path("workflow"));
            String activityId = String.valueOf(queued.get("activity_id"));
            String runId = String.valueOf(queued.get("run_id"));
            confirmed.put("draft_id", String.valueOf(current.get("id")));
            db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?",
                    confirmed.path("title").asText(), confirmed.toString(), activityId);
            db.update("UPDATE agent_runs SET plan_json=?, updated_at=? WHERE id=?",
                    confirmed.toString(), LocalDateTime.now().toString(), runId);
            List<String> missing = unresolvedMissingInformation(draft, intent);
            String runStatus = "pending";
            // 学习点: 缺信息时活动已经创建，但编排不会开始；等待用户通过 run_id 提交补充信息。
            if (!missing.isEmpty()) {
                lifecycle.pauseForInput(runId, activityId, missing);
                runStatus = "waiting_input";
            }
            return new PublishOutcome("activity", activityId, runId, runStatus, missing);
        }
        Map<String, Object> classInfo = classroom.requireClassManager(classId, account);
        if (classInfo.get("qq_group_openid") == null) throw ApiException.badRequest("班级尚未绑定 QQ 群，无法发布通知");
        String group = String.valueOf(classInfo.get("qq_group_openid"));
        if (!qq.isGroupAuthorized(String.valueOf(classInfo.get("organization_id")), group))
            throw ApiException.forbidden("班级 QQ 群绑定已失效，请重新选择授权群");
        Map<String, Object> sent = qq.sendGroup(group,
                "【班级通知】" + draft.path("title").asText() + "\n" + draft.path("description").asText(draft.path("summary").asText()),
                "agent-notice:" + current.get("id"));
        return new PublishOutcome("notice", sent.get("id") == null ? Ids.shortId() : String.valueOf(sent.get("id")),
                null, "queued", List.of());
    }

    private String activityText(JsonNode draft) {
        String text = draft.path("title").asText() + "。" + draft.path("description").asText(draft.path("summary").asText());
        String eventTime = draft.path("event_time").asText();
        return eventTime.isBlank() ? text : text + "。活动时间：" + eventTime;
    }

    private List<String> unresolvedMissingInformation(JsonNode draft, String intent) {
        // 学习点: 以当前草案字段重新判断缺口，避免用户手工填了时间后仍保留 AI 之前的旧提示。
        List<String> missing = new ArrayList<>();
        draft.path("missing_information").forEach(item -> {
            String question = item.asText();
            if (!question.isBlank() && (isTimeQuestion(question)
                    || draft.path("input").path("answers").path(question).asText().isBlank()))
                missing.add(question);
        });
        String eventTime = draft.path("event_time").asText();
        if (!eventTime.isBlank()) {
            missing.removeIf(this::isTimeQuestion);
        } else if ("activity".equals(intent)
                || draft.path("workflow").path("create_calendar").asBoolean(false)
                || draft.path("workflow").path("schedule_reminder").asBoolean(false)) {
            if (missing.stream().noneMatch(this::isTimeQuestion)) missing.add("请确认活动时间");
        }
        return missing;
    }

    private void addRunState(Map<String, Object> response, Object resultType, Object resultId) {
        if (!"activity".equals(resultType) || resultId == null) return;
        db.one("SELECT id, status FROM agent_runs WHERE kind='activity' AND ref_id=?", resultId)
                .ifPresent(run -> {
                    response.put("run_id", run.get("id"));
                    response.put("run_status", run.get("status"));
                });
    }

    private boolean isTimeQuestion(String text) {
        return text.contains("活动时间") || text.contains("活动日期") || text.contains("开始时间") || text.contains("event_time");
    }

    private void validateAssignees(String classId, JsonNode plan) {
        if (plan.path("tasks").isEmpty()) return;
        List<String> members = db.list(
                "SELECT a.id AS account_id FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                        "WHERE cm.class_id=? AND a.status='active'", classId)
                .stream().map(row -> String.valueOf(row.get("account_id"))).toList();
        for (JsonNode task : plan.path("tasks")) {
            if (!members.contains(task.path("account_id").asText()))
                ((ObjectNode)task).put("account_id", "").put("assignee", "待分配").put("unassigned_reason", "负责人未指定、已失效或不属于当前班级");
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private record PublishOutcome(String type, String id, String runId, String runStatus,
                                  List<String> missingInformation) {
    }
}

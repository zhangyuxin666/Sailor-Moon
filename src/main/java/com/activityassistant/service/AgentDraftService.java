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

    public AgentDraftService(Db db, ClassroomService classroom, ActivityService activities, AiClient ai,
                             AuditService audits, QqService qq, ObjectMapper mapper) {
        this.db = db;
        this.classroom = classroom;
        this.activities = activities;
        this.ai = ai;
        this.audits = audits;
        this.qq = qq;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> create(AccountPrincipal account, String classId, String rawInput) {
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以使用发布 Agent");
        Map<String, Object> classInfo = classroom.requireClassManager(classId, account);
        List<String> members = db.list("SELECT a.display_name FROM class_members cm JOIN accounts a ON a.id=cm.account_id " +
                "WHERE cm.class_id=? AND a.status='active' ORDER BY a.student_no, a.display_name", classId)
                .stream().map(row -> String.valueOf(row.get("display_name"))).toList();
        String context = "班级：" + classInfo.get("name") + "；成员数：" + members.size() + "；成员：" + String.join("、", members);
        String organizationId = String.valueOf(classInfo.get("organization_id"));
        JsonNode draft = ai.analyzeWorkRequest(rawInput, context, organizationId);
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
        Map<String, Object> current = get(account, id);
        if (!"draft".equals(current.get("status"))) throw ApiException.badRequest("只有未发布草案可以修改");
        validateDraft(draft);
        db.update("UPDATE agent_drafts SET intent_type=?, complexity=?, draft_json=?, updated_at=? WHERE id=?",
                draft.path("intent_type").asText(), draft.path("complexity").asText(), draft.toString(), LocalDateTime.now().toString(), id);
        audits.write("agent.draft_update", account.id(), String.valueOf(current.get("organization_id")), "agent_draft", id, null);
        return get(account, id);
    }

    public Map<String, Object> publish(AccountPrincipal account, String id) {
        Map<String, Object> current = get(account, id);
        if ("published".equals(current.get("status"))) return Map.of("status", "published", "result_type", current.get("result_type"),
                "result_id", current.get("result_id"), "idempotent", true);
        if (!"draft".equals(current.get("status"))) throw ApiException.badRequest("草案正在发布，请勿重复操作");
        if (db.update("UPDATE agent_drafts SET status='publishing', updated_at=? WHERE id=? AND status='draft'", LocalDateTime.now().toString(), id) == 0)
            throw ApiException.badRequest("草案正在发布，请勿重复操作");
        JsonNode draft = (JsonNode) current.get("draft");
        try {
            Map.Entry<String, String> result = execute(account, current, draft);
            db.update("UPDATE agent_drafts SET status='published', result_type=?, result_id=?, updated_at=? WHERE id=?",
                    result.getKey(), result.getValue(), LocalDateTime.now().toString(), id);
            audits.write("agent.draft_publish", account.id(), String.valueOf(current.get("organization_id")), result.getKey(), result.getValue(), Map.of("draft_id", id));
            return Map.of("status", "published", "result_type", result.getKey(), "result_id", result.getValue());
        } catch (RuntimeException exception) {
            db.update("UPDATE agent_drafts SET status='draft', updated_at=? WHERE id=?", LocalDateTime.now().toString(), id);
            throw exception;
        }
    }

    private Map.Entry<String, String> execute(AccountPrincipal account, Map<String, Object> current, JsonNode draft) {
        String intent = draft.path("intent_type").asText();
        String classId = String.valueOf(current.get("class_id"));
        if ("assignment".equals(intent)) {
            if (draft.path("deadline").asText().isBlank()) throw ApiException.badRequest("发布作业前必须确认截止时间");
            Map<String, Object> todo = classroom.createTodo(account, classId, new ApiModels.TodoCreateRequest(
                    draft.path("title").asText(), draft.path("description").asText(draft.path("summary").asText()),
                    draft.path("deadline").asText(), "homework"));
            return Map.entry("todo", String.valueOf(todo.get("id")));
        }
        if ("activity".equals(intent) || "survey".equals(intent)) {
            String text = draft.path("title").asText() + "。" + draft.path("description").asText(draft.path("summary").asText());
            Map<String, Object> queued = activities.queue(account, text, draft.path("workflow").path("publish_message").asBoolean(true),
                    classId, draft.path("workflow"));
            return Map.entry("activity", String.valueOf(queued.get("activity_id")));
        }
        Map<String, Object> classInfo = classroom.requireClassManager(classId, account);
        if (classInfo.get("qq_group_openid") == null) throw ApiException.badRequest("班级尚未绑定 QQ 群，无法发布通知");
        Map<String, Object> sent = qq.sendGroup(String.valueOf(classInfo.get("qq_group_openid")),
                "【班级通知】" + draft.path("title").asText() + "\n" + draft.path("description").asText(draft.path("summary").asText()),
                "agent-notice:" + current.get("id"));
        return Map.entry("notice", sent.get("id") == null ? Ids.shortId() : String.valueOf(sent.get("id")));
    }

    private void validateDraft(JsonNode draft) {
        if (!draft.isObject() || draft.path("intent_type").asText().isBlank() || draft.path("title").asText().isBlank() || !draft.path("workflow").isObject())
            throw ApiException.badRequest("草案结构无效");
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}

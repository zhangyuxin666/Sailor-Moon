package com.activityassistant.service;

import com.activityassistant.orchestration.AgentContext;
import com.activityassistant.orchestration.AgentResult;
import com.activityassistant.orchestration.RunStatus;
import com.activityassistant.persistence.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunService {
    private final Db db;
    private final ObjectMapper mapper;

    public AgentRunService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public void create(String id, String kind, String refId, String organizationId, String actorId) {
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO agent_runs (id, kind, ref_id, organization_id, actor_id, status, phase, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'planning', ?, ?) ON CONFLICT (kind, ref_id) DO NOTHING",
                id, kind, refId, organizationId, actorId, RunStatus.PENDING.wire(), now, now);
    }

    public void start(String refId) {
        update(refId, RunStatus.RUNNING.wire(), "planning", null, null, null);
    }

    public void phase(String refId, String phase) {
        update(refId, RunStatus.RUNNING.wire(), phase, null, null, null);
    }

    public void planned(String refId, Object plan) {
        update(refId, RunStatus.RUNNING.wire(), "execution", json(plan), null, null);
    }

    public void complete(String refId, Object result) {
        update(refId, RunStatus.SUCCEEDED.wire(), "delivery", null, json(result), null);
    }

    public void fail(String refId, Exception exception) {
        update(refId, RunStatus.FAILED.wire(), null, null, null, error(exception));
    }

    /**
     * 任务重试前回到待执行。
     *
     * V3 之后不再有独立的 retrying 状态：重试只是“回到 pending 再跑一次”，
     * 具体试到第几次由 background_jobs.attempts 记录。
     */
    public void requeued(String refId, Exception exception) {
        update(refId, RunStatus.PENDING.wire(), "planning", null, null, error(exception));
    }

    /** 写入统一的调用上下文。 */
    public void context(String refId, AgentContext context) {
        db.update("UPDATE agent_runs SET context_json=?, updated_at=? WHERE ref_id=?",
                json(context), LocalDateTime.now().toString(), refId);
    }

    /** 写入统一结果，并把状态同步到 agent_runs.status。 */
    public void resolve(String refId, AgentResult result) {
        db.update("UPDATE agent_runs SET resolution_json=?, status=COALESCE(?, status), error=?, updated_at=? WHERE ref_id=?",
                json(result), result.status().wire(), result.error(), LocalDateTime.now().toString(), refId);
    }

    public Map<String, Object> byRef(String kind, String refId) {
        return db.one("SELECT * FROM agent_runs WHERE kind=? AND ref_id=?", kind, refId).orElse(null);
    }

    public Map<String, Object> byId(String id) {
        return db.one("SELECT * FROM agent_runs WHERE id=?", id).orElse(null);
    }

    /**
     * 把 context_json / resolution_json 解析成结构化字段，供 /runs/{id} 返回。
     * 解析失败时返回 null 而不抛异常，避免一条脏数据让整个查询接口不可用。
     */
    public Map<String, Object> protocolOf(Map<String, Object> run) {
        AgentResult result = parseOrNull(run.get("resolution_json"), AgentResult.class);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("context", parseOrNull(run.get("context_json"), AgentContext.class));
        view.put("result", result);
        view.put("next_actions", result == null ? List.of() : result.nextActions());
        // 学习点: input 是从 input_json 反序列化出来的补充信息，便于前端恢复等待中的表单。
        view.put("input", parseObjectOrNull(run.get("input_json")));
        return view;
    }

    private <T> T parseOrNull(Object raw, Class<T> type) {
        if (raw == null) return null;
        String value = String.valueOf(raw);
        if (value.isBlank() || "null".equals(value)) return null;
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseObjectOrNull(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw);
        if (value.isBlank() || "null".equals(value)) return null;
        try {
            return mapper.readValue(value, Map.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private void update(String refId, String status, String phase, String plan, String result, String error) {
        db.update("UPDATE agent_runs SET status=COALESCE(?, status), phase=COALESCE(?, phase), " +
                        "plan_json=COALESCE(?, plan_json), result_json=COALESCE(?, result_json), error=?, updated_at=? " +
                        "WHERE ref_id=?",
                status, phase, plan, result, error, LocalDateTime.now().toString(), refId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String error(Throwable exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.substring(0, Math.min(message.length(), 2000));
    }
}

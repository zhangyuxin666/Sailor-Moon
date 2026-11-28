package com.activityassistant.service;

import com.activityassistant.orchestration.AgentContext;
import com.activityassistant.orchestration.AgentResult;
import com.activityassistant.orchestration.RunStatus;
import com.activityassistant.persistence.mapper.AgentRunMapper;
import com.activityassistant.support.PublicErrors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunService {
    private final AgentRunMapper runs;
    private final ObjectMapper mapper;

    public AgentRunService(AgentRunMapper runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    public void create(String id, String kind, String refId, String organizationId, String actorId) {
        String now = LocalDateTime.now().toString();
        runs.create(id, kind, refId, organizationId, actorId, RunStatus.PENDING.wire(), now, now);
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
        Map<String, Object> data = mapper.convertValue(result,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        AgentResult protocol = new AgentResult(RunStatus.SUCCEEDED, data, List.of(), List.of(), null);
        runs.complete(json(result), json(protocol), LocalDateTime.now().toString(), refId);
    }

    public void waiting(String id){
        var result=new AgentResult(RunStatus.WAITING_INPUT,Map.of("activity_id",id,"missing_information",List.of("请确认活动时间")),List.of(),List.of("provide_input"),null);
        runs.markWaiting(json(result),LocalDateTime.now().toString(),id);
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
        runs.saveContext(json(context), LocalDateTime.now().toString(), refId);
    }

    /** 写入统一结果，并把状态同步到 agent_runs.status。 */
    public void resolve(String refId, AgentResult result) {
        runs.saveResolution(json(result), result.status().wire(), result.error(), LocalDateTime.now().toString(), refId);
    }

    public Map<String, Object> byRef(String kind, String refId) {
        return runs.findByRef(kind, refId).orElse(null);
    }

    public Map<String, Object> byId(String id) {
        return runs.findById(id).orElse(null);
    }

    /**
     * 把 context_json / resolution_json 解析成结构化字段，供 /runs/{id} 返回。
     * 解析失败时返回 null 而不抛异常，避免一条脏数据让整个查询接口不可用。
     */
    public Map<String, Object> protocolOf(Map<String, Object> run) {
        AgentResult result = parseOrNull(run.get("resolution_json"), AgentResult.class);
        if (result != null) result = new AgentResult(result.status(), result.data(), result.citations(),
                result.nextActions(), PublicErrors.message(result.error()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("context", parseOrNull(run.get("context_json"), AgentContext.class));
        view.put("result", result);
        view.put("next_actions", result == null ? List.of() : result.nextActions());
        // 学习点: input 是从 input_json 反序列化出来的补充信息，便于前端恢复等待中的表单。
        view.put("input", parseObjectOrNull(run.get("input_json")));
        view.put("plan", parseObjectOrNull(run.get("plan_json")));
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
        runs.updateState(status, phase, plan, result, error, LocalDateTime.now().toString(), refId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String error(Throwable exception) {
        return PublicErrors.failure(exception);
    }
}

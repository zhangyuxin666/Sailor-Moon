package com.activityassistant.service;

import com.activityassistant.orchestration.AgentResult;
import com.activityassistant.orchestration.RunStatus;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates the parts of a run that are outside the worker's fixed workflow:
 * pausing for user input, saving that input, and making the run executable again.
 */
@Service
public class RunLifecycleService {
    private final Db db;
    private final ObjectMapper mapper;

    public RunLifecycleService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> saveInput(String runId, JsonNode input) {
        // 学习点: 输入只属于 waiting_input 的运行；先校验状态，再把 JSON 持久化，接口本身不自动恢复。
        Map<String, Object> run = requireRun(runId);
        RunStatus status = status(run);
        if (status != RunStatus.WAITING_INPUT) {
            throw ApiException.badRequest("只有等待补充信息的运行可以提交输入");
        }
        if (input == null || !input.isObject() || input.isEmpty()) {
            throw ApiException.badRequest("补充信息必须是非空 JSON 对象");
        }

        db.update("UPDATE agent_runs SET input_json=?, updated_at=? WHERE id=?",
                json(input), LocalDateTime.now().toString(), runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        result.put("status", RunStatus.WAITING_INPUT.wire());
        result.put("input", input);
        return result;
    }

    @Transactional
    public Map<String, Object> resume(String runId) {
        // 学习点: resume 先判断当前状态。pending/running 重复调用直接返回；终态拒绝恢复。
        Map<String, Object> run = requireRun(runId);
        RunStatus status = status(run);

        if (status == RunStatus.PENDING || status == RunStatus.RUNNING) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run_id", runId);
            result.put("status", status.wire());
            result.put("idempotent", true);
            return result;
        }
        if (status == RunStatus.SUCCEEDED || status == RunStatus.CANCELLED) {
            throw ApiException.badRequest("终态运行不能恢复");
        }
        if (status == RunStatus.WAITING_INPUT && !hasInput(run.get("input_json"))) {
            throw ApiException.badRequest("请先提交补充信息再恢复运行");
        }
        if (!status.canTransitionTo(RunStatus.PENDING)) {
            throw ApiException.badRequest("当前状态不能恢复: " + status.wire());
        }

        String activityId = String.valueOf(run.get("ref_id"));
        String now = LocalDateTime.now().toString();
        // 学习点: 恢复要同时改三处：工作流回 pending、活动回 queued、后台任务重新排队。
        db.update("UPDATE agent_runs SET status='pending', phase='planning', error=NULL, updated_at=? WHERE id=?",
                now, runId);
        db.update("UPDATE activities SET status='queued' WHERE id=?", activityId);
        db.update("UPDATE background_jobs SET status='queued', next_attempt_at=NULL, started_at=NULL, " +
                        "finished_at=NULL, heartbeat_at=NULL, locked_by=NULL, error=NULL, updated_at=? WHERE id=?",
                now, runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        result.put("status", RunStatus.PENDING.wire());
        result.put("idempotent", false);
        return result;
    }

    @Transactional
    public void pauseForInput(String runId, String activityId, List<String> missingInformation) {
        // 学习点: 暂停时 agent_run 记住 waiting_input 和缺失信息，而 background_job 本次执行结束。
        AgentResult result = new AgentResult(
                RunStatus.WAITING_INPUT,
                Map.of("missing_information", missingInformation, "activity_id", activityId),
                List.of(),
                List.of("provide_input"),
                null);
        String now = LocalDateTime.now().toString();
        db.update("UPDATE agent_runs SET status='waiting_input', phase='planning', resolution_json=?, " +
                        "error=NULL, updated_at=? WHERE id=?",
                json(result), now, runId);
        db.update("UPDATE activities SET status='waiting_input' WHERE id=?", activityId);
        db.update("UPDATE background_jobs SET status='succeeded', finished_at=?, error=NULL, " +
                        "locked_by=NULL, heartbeat_at=NULL, updated_at=? WHERE id=?",
                now, now, runId);
    }

    private Map<String, Object> requireRun(String runId) {
        return db.one("SELECT * FROM agent_runs WHERE id=?", runId)
                .orElseThrow(() -> ApiException.notFound("运行记录不存在: " + runId));
    }

    private RunStatus status(Map<String, Object> run) {
        return RunStatus.fromWire(String.valueOf(run.get("status")))
                .orElseThrow(() -> ApiException.badRequest("运行状态无效"));
    }

    private boolean hasInput(Object raw) {
        if (raw == null) return false;
        String value = String.valueOf(raw);
        if (value.isBlank() || "null".equals(value)) return false;
        try {
            JsonNode node = mapper.readTree(value);
            return node.isObject() && !node.isEmpty();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

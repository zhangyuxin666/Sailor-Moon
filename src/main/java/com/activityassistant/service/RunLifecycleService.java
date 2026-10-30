package com.activityassistant.service;

import com.activityassistant.orchestration.AgentResult;
import com.activityassistant.orchestration.RunStatus;
import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
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
import java.util.Set;

/** Persists supplemental input and resumes the same reviewed execution snapshot. */
@Service
public class RunLifecycleService {
    private static final Set<String> INPUT_KEYS = Set.of("title", "description", "event_time",
            "form_fields", "materials", "tasks", "remind_minutes_before", "answers");
    private final Db db;
    private final ObjectMapper mapper;

    public RunLifecycleService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> saveInput(String runId, JsonNode input) {
        Map<String, Object> run = requireRun(runId);
        if (status(run) != RunStatus.WAITING_INPUT)
            throw ApiException.badRequest("只有等待补充信息的运行可以提交输入");
        if (input == null || !input.isObject() || input.isEmpty() || input.toString().length() > 100_000)
            throw ApiException.badRequest("补充信息必须是非空且有效的 JSON 对象");
        input.fieldNames().forEachRemaining(key -> {
            if (!INPUT_KEYS.contains(key)) throw ApiException.badRequest("不支持的补充字段：" + key);
        });

        String activityId = String.valueOf(run.get("ref_id"));
        Map<String, Object> activity = requireActivity(activityId);
        ObjectNode mergedInput = object(run.get("input_json"));
        mergeInput(mergedInput, input);
        ObjectNode plan = activityPlan(activity);
        applyInput(plan, mergedInput);
        ConfirmedPlans.validate(plan);
        List<String> missing = remainingMissing(run, plan, mergedInput);
        AgentResult result = waitingResult(activityId, missing);
        String now = LocalDateTime.now().toString();
        db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?",
                plan.path("title").asText(), plan.toString(), activityId);
        db.update("UPDATE agent_runs SET input_json=?, plan_json=?, resolution_json=?, updated_at=? WHERE id=?",
                mergedInput.toString(), plan.toString(), json(result), now, runId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", runId);
        response.put("status", RunStatus.WAITING_INPUT.wire());
        response.put("input", mergedInput);
        response.put("plan", plan);
        response.put("missing_information", missing);
        return response;
    }

    @Transactional
    public Map<String, Object> resume(String runId) {
        Map<String, Object> run = requireRun(runId);
        RunStatus status = status(run);
        if (status == RunStatus.PENDING || status == RunStatus.RUNNING)
            return resumeResponse(runId, status, true);
        if (status == RunStatus.SUCCEEDED || status == RunStatus.CANCELLED)
            throw ApiException.badRequest("终态运行不能恢复");
        if (!status.canTransitionTo(RunStatus.PENDING))
            throw ApiException.badRequest("当前状态不能恢复: " + status.wire());

        Map<String, Object> job = db.one("SELECT status FROM background_jobs WHERE id=? FOR UPDATE", runId)
                .orElseThrow(() -> ApiException.notFound("后台任务不存在"));
        if ("running".equals(job.get("status")))
            throw ApiException.badRequest("后台任务仍在收尾，请稍后再恢复");
        String activityId = String.valueOf(run.get("ref_id"));
        if (status == RunStatus.WAITING_INPUT) {
            ObjectNode input = object(run.get("input_json"));
            if (input.isEmpty()) throw ApiException.badRequest("请先提交补充信息再恢复运行");
            ObjectNode plan = activityPlan(requireActivity(activityId));
            applyInput(plan, input);
            ConfirmedPlans.validate(plan);
            List<String> missing = remainingMissing(run, plan, input);
            if (!missing.isEmpty())
                throw ApiException.badRequest("仍需补充信息：" + String.join("；", missing));
            db.update("UPDATE activities SET title=?, plan_json=? WHERE id=?",
                    plan.path("title").asText(), plan.toString(), activityId);
            db.update("UPDATE agent_runs SET plan_json=?, updated_at=? WHERE id=?",
                    plan.toString(), LocalDateTime.now().toString(), runId);
        }

        String now = LocalDateTime.now().toString();
        db.update("UPDATE agent_runs SET status='pending', phase='planning', resolution_json=NULL, error=NULL, updated_at=? " +
                "WHERE id=? AND status IN ('waiting_input','failed')", now, runId);
        db.update("UPDATE activities SET status='queued' WHERE id=?", activityId);
        // Explicit manual recovery starts a fresh bounded retry budget; successful business steps remain cached.
        int changed = db.update("UPDATE background_jobs SET status='queued', attempts=0, next_attempt_at=NULL, started_at=NULL, " +
                        "finished_at=NULL, heartbeat_at=NULL, locked_by=NULL, error=NULL, updated_at=? " +
                        "WHERE id=? AND status<>'running'", now, runId);
        if (changed != 1) throw ApiException.badRequest("后台任务状态已变化，请刷新后重试");
        return resumeResponse(runId, RunStatus.PENDING, false);
    }

    @Transactional
    public void pauseForInput(String runId, String activityId, List<String> missingInformation) {
        String now = LocalDateTime.now().toString();
        db.update("UPDATE agent_runs SET status='waiting_input', phase='planning', resolution_json=?, " +
                        "error=NULL, updated_at=? WHERE id=?",
                json(waitingResult(activityId, missingInformation)), now, runId);
        db.update("UPDATE activities SET status='waiting_input' WHERE id=?", activityId);
        db.update("UPDATE background_jobs SET status='succeeded', finished_at=?, error=NULL, " +
                        "locked_by=NULL, heartbeat_at=NULL, updated_at=? WHERE id=?",
                now, now, runId);
    }

    private void mergeInput(ObjectNode merged, JsonNode patch) {
        patch.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("answers".equals(key)) {
                if (!value.isObject() || value.size() > 100)
                    throw ApiException.badRequest("补充回答必须是问题与文本的对应关系");
                ObjectNode answers = merged.path("answers").isObject()
                        ? merged.path("answers").deepCopy() : mapper.createObjectNode();
                value.fields().forEachRemaining(answer -> {
                    if (answer.getKey().isBlank() || answer.getKey().length() > 1000
                            || !answer.getValue().isTextual() || answer.getValue().asText().isBlank()
                            || answer.getValue().asText().length() > 5000)
                        throw ApiException.badRequest("补充回答必须为非空文本");
                    answers.set(answer.getKey(), answer.getValue());
                });
                merged.set("answers", answers);
            } else {
                if (Set.of("title", "description", "event_time").contains(key) && !value.isTextual())
                    throw ApiException.badRequest("补充标题、说明和时间必须为文本");
                merged.set(key, value);
            }
        });
    }

    private void applyInput(ObjectNode plan, ObjectNode input) {
        for (String key : List.of("title", "event_time", "tasks", "materials", "remind_minutes_before")) {
            if (input.has(key)) plan.set(key, input.path(key).deepCopy());
        }
        if (input.has("form_fields"))
            plan.set("form_fields", ConfirmedPlans.fields(input.path("form_fields"), mapper));
        String base = plan.path("description_before_input").asText(plan.path("description").asText(""));
        if (input.has("description")) base = input.path("description").asText();
        ObjectNode answers = mapper.createObjectNode();
        if (plan.path("supplemental_information").isObject())
            answers.setAll((ObjectNode) plan.path("supplemental_information"));
        if (input.path("answers").isObject()) answers.setAll((ObjectNode) input.path("answers"));
        if (!answers.isEmpty()) {
            input.set("answers", answers.deepCopy());
            plan.put("description_before_input", base);
            plan.set("supplemental_information", answers.deepCopy());
            StringBuilder description = new StringBuilder(base);
            description.append("\n补充信息：");
            answers.fields().forEachRemaining(entry ->
                    description.append("\n").append(entry.getKey()).append("：").append(entry.getValue().asText()));
            plan.put("description", description.toString());
        } else {
            plan.put("description", base);
        }
    }

    private List<String> remainingMissing(Map<String, Object> run, JsonNode plan, JsonNode input) {
        List<String> missing = new ArrayList<>();
        JsonNode prior = object(run.get("resolution_json")).path("data").path("missing_information");
        for (JsonNode question : prior) {
            String text = question.asText();
            if (isTimeQuestion(text) && !plan.path("event_time").asText().isBlank()) continue;
            if (!input.path("answers").path(text).asText().isBlank()) continue;
            if (!text.isBlank()) missing.add(text);
        }
        boolean requiresTime = plan.path("requires_event_time").asBoolean(false);
        for (JsonNode question : prior) requiresTime |= isTimeQuestion(question.asText());
        if (requiresTime && plan.path("event_time").asText().isBlank()
                && missing.stream().noneMatch(this::isTimeQuestion))
            missing.add("请确认活动时间");
        return missing;
    }

    private ObjectNode activityPlan(Map<String, Object> activity) {
        ObjectNode plan = object(activity.get("plan_json"));
        if (plan.isEmpty())
            throw ApiException.badRequest("历史运行缺少已确认方案，请重新生成草案后发布");
        if (!plan.has("form_fields")) plan.set("form_fields", ConfirmedPlans.fields(mapper.nullNode(), mapper));
        if (!plan.has("tasks")) plan.set("tasks", mapper.createArrayNode());
        if (!plan.has("materials")) plan.set("materials", mapper.createArrayNode());
        if (!plan.has("remind_minutes_before")) plan.put("remind_minutes_before", 60);
        return plan;
    }

    private Map<String, Object> requireRun(String runId) {
        Map<String,Object> ref=db.one("SELECT * FROM agent_runs WHERE id=?",runId).orElseThrow(()->ApiException.notFound("运行不存在"));
        Map<String,Object> activity=db.one("SELECT status FROM activities WHERE id=? FOR UPDATE",ref.get("ref_id")).orElseThrow(()->ApiException.notFound("活动不存在"));
        if("cancelled".equals(activity.get("status")))throw ApiException.badRequest("活动已取消，不能恢复或补充");
        return db.one("SELECT * FROM agent_runs WHERE id=? FOR UPDATE", runId)
                .orElseThrow(() -> ApiException.notFound("运行记录不存在: " + runId));
    }

    private Map<String, Object> requireActivity(String activityId) {
        return db.one("SELECT title, raw_input, plan_json FROM activities WHERE id=? FOR UPDATE", activityId)
                .orElseThrow(() -> ApiException.notFound("活动不存在"));
    }

    private ObjectNode object(Object raw) {
        if (raw == null || "null".equals(String.valueOf(raw))) return mapper.createObjectNode();
        try {
            JsonNode parsed = mapper.readTree(String.valueOf(raw));
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : mapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            throw ApiException.badRequest("已保存的运行数据格式无效，请联系管理者");
        }
    }

    private boolean isTimeQuestion(String text) {
        return text.contains("活动时间") || text.contains("活动日期") || text.contains("开始时间") || text.contains("event_time");
    }

    private RunStatus status(Map<String, Object> run) {
        return RunStatus.fromWire(String.valueOf(run.get("status")))
                .orElseThrow(() -> ApiException.badRequest("运行状态无效"));
    }

    private AgentResult waitingResult(String activityId, List<String> missing) {
        return new AgentResult(RunStatus.WAITING_INPUT,
                Map.of("missing_information", missing, "activity_id", activityId),
                List.of(), missing.isEmpty() ? List.of("resume") : List.of("provide_input"), null);
    }

    private Map<String, Object> resumeResponse(String runId, RunStatus status, boolean idempotent) {
        return Map.of("run_id", runId, "status", status.wire(), "idempotent", idempotent);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}

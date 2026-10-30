package com.activityassistant.service;

import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts the reviewed draft into a deterministic execution snapshot. */
final class ConfirmedPlans {
    private static final List<String> TOOLS = List.of(
            "create_form", "assign_tasks", "create_calendar", "schedule_reminder", "publish_message");
    private static final Set<String> WORKFLOW_KEYS = Set.of(
            "create_plan", "create_form", "assign_tasks", "create_calendar",
            "schedule_reminder", "publish_message", "collect_submission");

    private ConfirmedPlans() {}

    static ObjectNode fromDraft(JsonNode draft, ObjectMapper mapper) {
        ObjectNode plan = mapper.createObjectNode();
        JsonNode generated = draft.path("execution_plan");
        JsonNode generation=generated.path("generation").isObject()?generated.path("generation"):draft.path("generation");
        if(generation.isObject())plan.set("generation",generation.deepCopy());
        if(draft.path("generation").isObject())plan.set("analysis_generation",draft.path("generation").deepCopy());
        plan.put("title", draft.path("title").asText());
        plan.put("description", draft.path("description").asText(draft.path("summary").asText()));
        plan.put("event_time", draft.path("event_time").asText());
        plan.set("form_fields", fields(draft.path("form_fields"), mapper));
        plan.set("materials", generated.path("materials").isArray()
                ? generated.path("materials").deepCopy() : mapper.createArrayNode());
        plan.set("tasks", generated.path("tasks").isArray()
                ? generated.path("tasks").deepCopy() : mapper.createArrayNode());
        plan.put("remind_minutes_before", generated.path("remind_minutes_before").asInt(60));
        JsonNode workflow = draft.path("workflow");
        ArrayNode tools = mapper.createArrayNode();
        for (String tool : TOOLS) {
            if (workflow.path(tool).asBoolean(false)) tools.add(mapper.createObjectNode().put("tool", tool));
        }
        plan.set("tool_intents", tools);
        plan.put("requires_event_time", "activity".equals(draft.path("intent_type").asText())
                || workflow.path("create_calendar").asBoolean(false)
                || workflow.path("schedule_reminder").asBoolean(false));
        plan.put("plan_source", "confirmed_draft");
        JsonNode answers = draft.path("input").path("answers");
        if (!answers.isMissingNode()) {
            if (!answers.isObject() || answers.size() > 100)
                throw ApiException.badRequest("补充回答必须为问题与文本的对应关系");
            StringBuilder description = new StringBuilder(plan.path("description").asText());
            answers.fields().forEachRemaining(entry -> {
                if (entry.getKey().isBlank() || entry.getKey().length() > 1000
                        || !entry.getValue().isTextual() || entry.getValue().asText().isBlank()
                        || entry.getValue().asText().length() > 5000)
                    throw ApiException.badRequest("补充回答必须为非空文本");
                description.append("\n").append(entry.getKey()).append("：").append(entry.getValue().asText());
            });
            if (!answers.isEmpty()) {
                plan.put("description_before_input", plan.path("description").asText());
                plan.put("description", description.toString());
                plan.set("supplemental_information", answers.deepCopy());
            }
        }
        validate(plan);
        return plan;
    }

    static ArrayNode fields(JsonNode raw, ObjectMapper mapper) {
        if (!raw.isMissingNode() && !raw.isNull() && !raw.isArray())
            throw ApiException.badRequest("问卷题目必须为数组");
        ArrayNode result = mapper.createArrayNode();
        Set<String> names = new HashSet<>();
        if (raw.isArray()) {
            if (raw.size() > 100) throw ApiException.badRequest("问卷题目不能超过 100 项");
            for (JsonNode field : raw) {
                String name = field.path("name").asText();
                String label = field.path("label").asText();
                String type = field.path("type").asText("text");
                if (!field.isObject() || !name.matches("[A-Za-z][A-Za-z0-9_]{0,30}")
                        || label.isBlank() || label.length() > 255 || !names.add(name)
                        || !Set.of("text", "select").contains(type))
                    throw ApiException.badRequest("问卷题目名称、标签或类型无效，名称不得重复");
                ObjectNode normalized = field.deepCopy();
                normalized.put("type", type);
                if (field.has("required") && !field.path("required").isBoolean())
                    throw ApiException.badRequest("问卷必填标记必须为布尔值");
                if (field.has("options") && (!field.path("options").isArray()
                        || field.path("options").size() > 100))
                    throw ApiException.badRequest("问卷选项必须为数组且不超过 100 项");
                for (JsonNode option : field.path("options")) {
                    if (!option.isTextual() || option.asText().isBlank() || option.asText().length() > 1000)
                        throw ApiException.badRequest("问卷选项必须为有效文本");
                }
                if ("select".equals(type) && field.path("options").isEmpty())
                    throw ApiException.badRequest("选择题至少包含一个选项");
                if ("name".equals(name) || "contact".equals(name)) {
                    normalized.put("type", "text").put("required", true);
                    normalized.remove("options");
                }
                result.add(normalized);
            }
        }
        if (!names.contains("name"))
            result.insert(0, mapper.createObjectNode().put("name", "name").put("label", "姓名")
                    .put("type", "text").put("required", true));
        if (!names.contains("contact"))
            result.insert(Math.min(1, result.size()), mapper.createObjectNode().put("name", "contact")
                    .put("label", "联系方式").put("type", "text").put("required", true));
        return result;
    }

    static void validateDraft(JsonNode draft) {
        if (draft == null || !draft.isObject()
                || !Set.of("assignment", "activity", "survey", "notice").contains(draft.path("intent_type").asText())
                || draft.path("title").asText().isBlank() || draft.path("title").asText().length() > 255
                || !draft.path("workflow").isObject())
            throw ApiException.badRequest("草案结构无效");
        draft.path("workflow").fields().forEachRemaining(entry -> {
            if (!WORKFLOW_KEYS.contains(entry.getKey()) || !entry.getValue().isBoolean())
                throw ApiException.badRequest("草案流程配置无效");
        });
        if (!draft.path("deadline").asText().isBlank()) validateTime(draft.path("deadline").asText());
    }

    static void validate(JsonNode plan) {
        if (!plan.isObject() || plan.path("title").asText().isBlank()
                || plan.path("title").asText().length() > 255)
            throw ApiException.badRequest("活动标题无效");
        String time = plan.path("event_time").asText();
        if (!time.isBlank()) validateTime(time);
        if (!plan.path("tasks").isArray() || plan.path("tasks").size() > 1000
                || !plan.path("materials").isArray() || plan.path("materials").size() > 1000)
            throw ApiException.badRequest("任务和物料必须为数组且不超过 1000 项");
        for (JsonNode material : plan.path("materials")) {
            if (!material.isTextual() || material.asText().length() > 1000)
                throw ApiException.badRequest("物料必须为文本");
        }
        for (JsonNode task : plan.path("tasks")) {
            TaskManagementService.validateTask(task);
            if(!task.path("deadline").asText().isBlank()) validateTime(task.path("deadline").asText());
            if (!task.isObject() || task.path("title").asText().isBlank()
                    || task.path("title").asText().length() > 255
                    || task.path("account_id").asText().length() > 32)
                throw ApiException.badRequest("任务必须包含有效标题和负责人账号 ID");
        }
        if (!plan.path("remind_minutes_before").canConvertToInt()
                || plan.path("remind_minutes_before").asInt() < 0
                || plan.path("remind_minutes_before").asInt() > 525600)
            throw ApiException.badRequest("提醒提前量无效");
    }

    static void validateTime(String value) {
        try {
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")) OffsetDateTime.parse(value);
            else LocalDateTime.parse(value);
        } catch (Exception exception) {
            throw ApiException.badRequest("请提供有效的 ISO 日期时间");
        }
    }
}

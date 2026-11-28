package com.activityassistant.orchestration;

import com.activityassistant.persistence.mapper.ExecutionJournalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** Persists workflow steps and idempotent tool results. */
@Service
public class ExecutionJournal {
    private final ExecutionJournalMapper journal;
    private final ObjectMapper mapper;

    public ExecutionJournal(ExecutionJournalMapper journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> find(String key, String tool) {
        // 学习点: 不只查 result_json，还要把数据库里的 JSON 字符串恢复成 Java Map。
        String result = journal.findResult(key, tool);
        if (result == null) return null;
        try {
            return (Map<String, Object>) mapper.readValue(result, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void save(String key, String tool, Map<String, Object> result) {
        // 学习点: 同一 key 已经存在时保留第一次成功结果，不覆盖也不报错。
        journal.saveResult(key, tool, json(result), LocalDateTime.now().toString());
    }

    public void record(String activityId, String phase, String step, String status, String detail,
                       String idempotencyKey, Object input, Object output, String error) {
        String shortDetail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500));
        Long old = idempotencyKey == null ? null : journal.findStepId(activityId, idempotencyKey);
        var data = new ExecutionJournalMapper.StepData(status, shortDetail, phase,
                input == null ? null : json(input), output == null ? null : json(output), error);
        // 学习点: steps 一行表示一个逻辑步骤；第一次插入，后续重试更新同一行并累计 attempts。
        if (old == null) {
            journal.insertStep(activityId, step, idempotencyKey, LocalDateTime.now().toString(), data);
        } else {
            journal.updateStep(old, data);
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

package com.activityassistant.orchestration;

import com.activityassistant.persistence.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** Persists workflow steps and idempotent tool results. */
@Service
public class ExecutionJournal {
    private final Db db;
    private final ObjectMapper mapper;

    public ExecutionJournal(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> find(String key, String tool) {
        // 学习点: 不只查 result_json，还要把数据库里的 JSON 字符串恢复成 Java Map。
        return db.one("SELECT result_json FROM idempotency_keys WHERE key=? AND tool_name=?", key, tool)
                .map(row -> {
                    try {
                        return (Map<String, Object>) mapper.readValue(String.valueOf(row.get("result_json")), Map.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException(exception);
                    }
                }).orElse(null);
    }

    public void save(String key, String tool, Map<String, Object> result) {
        // 学习点: 同一 key 已经存在时保留第一次成功结果，不覆盖也不报错。
        db.update("INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (key) DO NOTHING",
                key, tool, json(result), LocalDateTime.now().toString());
    }

    public void record(String activityId, String phase, String step, String status, String detail,
                       String idempotencyKey, Object input, Object output, String error) {
        String shortDetail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500));
        Map<String, Object> old = idempotencyKey == null ? null : db.one(
                "SELECT id, attempts FROM steps WHERE activity_id=? AND idempotency_key=?",
                activityId, idempotencyKey).orElse(null);
        // 学习点: steps 一行表示一个逻辑步骤；第一次插入，后续重试更新同一行并累计 attempts。
        if (old == null) {
            db.update("INSERT INTO steps (activity_id, step, status, detail, created_at, phase, idempotency_key, input_json, output_json, error, attempts) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                    activityId, step, status, shortDetail, LocalDateTime.now().toString(), phase, idempotencyKey,
                    input == null ? null : json(input), output == null ? null : json(output), error);
        } else {
            db.update("UPDATE steps SET status=?, detail=?, phase=?, input_json=COALESCE(?, input_json), " +
                            "output_json=COALESCE(?, output_json), error=?, attempts=attempts+1 WHERE id=?",
                    status, shortDetail, phase, input == null ? null : json(input), output == null ? null : json(output),
                    error, old.get("id"));
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

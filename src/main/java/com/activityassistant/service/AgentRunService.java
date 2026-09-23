package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
                        "VALUES (?, ?, ?, ?, ?, 'queued', 'planning', ?, ?) ON CONFLICT (kind, ref_id) DO NOTHING",
                id, kind, refId, organizationId, actorId, now, now);
    }

    public void start(String refId) {
        update(refId, "running", "planning", null, null, null);
    }

    public void phase(String refId, String phase) {
        update(refId, "running", phase, null, null, null);
    }

    public void planned(String refId, Object plan) {
        update(refId, "running", "execution", json(plan), null, null);
    }

    public void complete(String refId, Object result) {
        update(refId, "succeeded", "delivery", null, json(result), null);
    }

    public void fail(String refId, Exception exception) {
        String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        update(refId, "failed", null, null, null, error.substring(0, Math.min(error.length(), 2000)));
    }

    public void retrying(String refId, Exception exception) {
        String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        update(refId, "retrying", "planning", null, null, error.substring(0, Math.min(error.length(), 2000)));
    }

    public Map<String, Object> byRef(String kind, String refId) {
        return db.one("SELECT * FROM agent_runs WHERE kind=? AND ref_id=?", kind, refId).orElse(null);
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
}

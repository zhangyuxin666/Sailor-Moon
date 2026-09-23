package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BackgroundJobService {
    private final Db db;
    private final AgentRunService agentRuns;
    private final String workerId = UUID.randomUUID().toString();

    public BackgroundJobService(Db db, AgentRunService agentRuns) {
        this.db = db;
        this.agentRuns = agentRuns;
    }

    @Transactional
    public Map<String, Object> enqueue(String kind, String refId) {
        Map<String, Object> old = db.one("SELECT * FROM background_jobs WHERE kind=? AND ref_id=?", kind, refId).orElse(null);
        if (old != null) return old;
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        db.update("INSERT INTO background_jobs (id, kind, ref_id, status, attempts, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'queued', 0, ?, ?) ON CONFLICT (kind, ref_id) DO NOTHING",
                id, kind, refId, now, now);
        return db.required("SELECT * FROM background_jobs WHERE kind=? AND ref_id=?", kind, refId);
    }

    @Transactional
    public Map<String, Object> claimNext() {
        String now = LocalDateTime.now().toString();
        Map<String, Object> row = db.one("SELECT * FROM background_jobs WHERE status='queued' " +
                "AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1", now).orElse(null);
        if (row == null) return null;
        db.update("UPDATE background_jobs SET status='running', attempts=attempts+1, started_at=?, heartbeat_at=?, " +
                "locked_by=?, next_attempt_at=NULL, updated_at=? WHERE id=?", now, now, workerId, now, row.get("id"));
        Map<String, Object> claimed = new LinkedHashMap<>(row);
        claimed.put("attempts", ((Number) row.get("attempts")).intValue() + 1);
        claimed.put("status", "running");
        return claimed;
    }

    public void finish(String id) {
        String now = LocalDateTime.now().toString();
        db.update("UPDATE background_jobs SET status='succeeded', finished_at=?, error=NULL, locked_by=NULL, heartbeat_at=NULL, updated_at=? WHERE id=?", now, now, id);
    }

    public String fail(Map<String, Object> job, Exception exception) {
        int attempts = ((Number) job.get("attempts")).intValue();
        boolean retryable = !(exception instanceof ApiException apiException) || apiException.status().is5xxServerError();
        String status = retryable && attempts < 3 ? "queued" : "failed";
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String now = LocalDateTime.now().toString();
        String nextAttempt = "queued".equals(status)
                ? LocalDateTime.now().plusSeconds(Math.min(60, 1L << attempts)).toString() : null;
        db.update("UPDATE background_jobs SET status=?, error=?, finished_at=?, next_attempt_at=?, locked_by=NULL, heartbeat_at=NULL, updated_at=? WHERE id=?", status,
                message.substring(0, Math.min(message.length(), 2000)), "failed".equals(status) ? now : null,
                nextAttempt, now, job.get("id"));
        return status;
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> job = db.one("SELECT * FROM background_jobs WHERE id=?", id).orElse(null);
        if (job == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(job);
        Map<String, Object> run = agentRuns.byRef(String.valueOf(job.get("kind")), String.valueOf(job.get("ref_id")));
        if (run != null) result.put("agent_run", run);
        return result;
    }
}

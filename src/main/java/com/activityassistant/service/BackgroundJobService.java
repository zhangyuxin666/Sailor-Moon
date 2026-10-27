package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import com.activityassistant.support.PublicErrors;
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
    @org.springframework.beans.factory.annotation.Value("${WORKER_LEASE_SECONDS:30}")
    private int leaseSeconds=30;
    @org.springframework.beans.factory.annotation.Value("${WORKER_MAX_SECONDS:600}")
    private int maxSeconds=600;
    @org.springframework.beans.factory.annotation.Value("${APP_INSTANCE_NAME:local}")
    private String instance="local";
    public int maxMillis(){return Math.max(2,maxSeconds)*1000;}
    public int heartbeatMillis(){return Math.max(250,leaseSeconds*1000/3);}

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
        String token=UUID.randomUUID().toString();
        db.update("UPDATE background_jobs SET status='running', attempts=attempts+1, started_at=?, heartbeat_at=?, locked_by=?, worker_instance=?, next_attempt_at=NULL, updated_at=?, lease_version=lease_version+1, lease_until=clock_timestamp()+(? * interval '1 second'), deadline_at=clock_timestamp()+(? * interval '1 second') WHERE id=?",
                now,now,token,instance,now,Math.max(2,leaseSeconds),Math.max(2,maxSeconds),row.get("id"));
        Map<String,Object> claimed=new LinkedHashMap<>(row);
        claimed.put("attempts",((Number)row.get("attempts")).intValue()+1);
        claimed.put("worker_instance",instance);claimed.put("status","running");claimed.put("locked_by",token);
        claimed.put("lease_version",((Number)row.get("lease_version")).longValue()+1);
        return claimed;
    }

    public boolean heartbeat(Map<String,Object> job) {
        String now=LocalDateTime.now().toString();
        return db.update("UPDATE background_jobs SET heartbeat_at=?,lease_until=LEAST(deadline_at,clock_timestamp()+(? * interval '1 second')),updated_at=? WHERE id=? AND status='running' AND locked_by=? AND lease_version=? AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()",
                now,Math.max(2,leaseSeconds),now,job.get("id"),job.get("locked_by"),job.get("lease_version"))==1;
    }
    public void finish(Map<String,Object> job) {
        JobLease.verify(db);
        String now=LocalDateTime.now().toString();
        db.update("UPDATE background_jobs SET status='succeeded',finished_at=?,error=NULL,locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='running' AND locked_by=? AND lease_version=? AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()",
                now,now,job.get("id"),job.get("locked_by"),job.get("lease_version"));
    }
    public void cancel(Map<String,Object> job) {
        db.update("UPDATE background_jobs SET status='failed',error='活动已取消',finished_at=?,locked_by=NULL,heartbeat_at=NULL,lease_until=NULL WHERE id=? AND status='running' AND locked_by=? AND lease_version=?",
                LocalDateTime.now().toString(),job.get("id"),job.get("locked_by"),job.get("lease_version"));
    }
    @Transactional
    public String fail(Map<String,Object> job,Exception exception) {
        JobLease.verify(db);
        int attempts=((Number)job.get("attempts")).intValue();
        boolean retryable=!(exception instanceof ApiException e)||e.status().is5xxServerError();
        String status=retryable&&attempts<3?"queued":"failed";
        String now=LocalDateTime.now().toString(),error=PublicErrors.failure(exception);
        int changed=db.update("UPDATE background_jobs SET status=?,error=?,finished_at=?,next_attempt_at=?,locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='running' AND locked_by=? AND lease_version=? AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp()",
                status,error,"failed".equals(status)?now:null,"queued".equals(status)?LocalDateTime.now().plusSeconds(1L<<attempts).toString():null,now,job.get("id"),job.get("locked_by"),job.get("lease_version"));
        if(changed!=1)throw new JobLease.Lost();
        synchronize(job,status,error);
        return status;
    }
    @Transactional
    public void recover(Map<String,Object> candidate) {
        Map<String,Object> job=db.one("SELECT * FROM background_jobs WHERE id=? AND status='running' AND (lease_until IS NULL OR lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp()) FOR UPDATE",candidate.get("id")).orElse(null);
        if(job==null)return;
        String status=((Number)job.get("attempts")).intValue()<3?"queued":"failed";
        String now=LocalDateTime.now().toString(),error="执行租约到期或任务超时，已中止旧执行者";
        db.update("UPDATE background_jobs SET status=?,error=?,locked_by=NULL,heartbeat_at=NULL,lease_until=NULL,lease_version=lease_version+1,next_attempt_at=?,finished_at=?,updated_at=? WHERE id=?",status,error,now,"failed".equals(status)?now:null,now,job.get("id"));
        synchronize(job,status,error);
    }
    private void synchronize(Map<String,Object> job,String status,String error) {
        if("failed".equals(status)&&("reminder".equals(job.get("kind"))||"todo_reminder".equals(job.get("kind")))) {
            String table="reminder".equals(job.get("kind"))?"reminders":"todo_reminders";
            db.update("UPDATE "+table+" SET status='failed',error=? WHERE id=? AND status='scheduled'",error,job.get("ref_id"));
        }
        if("failed".equals(status)&&"activity_change".equals(job.get("kind")))db.update("UPDATE activity_changes SET notification_status='failed',error=? WHERE id=? AND notification_status='pending'",error,job.get("ref_id"));
        if("activity".equals(job.get("kind"))) {
            String runStatus="queued".equals(status)?"pending":"failed";
            db.update("UPDATE activities SET status=? WHERE id=? AND status NOT IN ('cancelled','finished','ready')",status,job.get("ref_id"));
            db.update("UPDATE agent_runs SET status=?,error=?,updated_at=? WHERE kind='activity' AND ref_id=? AND status NOT IN ('cancelled','succeeded')",runStatus,error,LocalDateTime.now().toString(),job.get("ref_id"));
        }
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> job = db.one("SELECT * FROM background_jobs WHERE id=?", id).orElse(null);
        if (job == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(job);
        result.remove("locked_by");
        result.put("error", PublicErrors.message(job.get("error")));
        Map<String, Object> run = agentRuns.byRef(String.valueOf(job.get("kind")), String.valueOf(job.get("ref_id")));
        if (run != null) {
            Map<String, Object> publicRun = new LinkedHashMap<>(run);
            publicRun.keySet().removeIf(key -> key.endsWith("_json"));
            publicRun.put("error", PublicErrors.message(run.get("error")));
            result.put("agent_run", publicRun);
            result.putAll(agentRuns.protocolOf(run));
        }
        return result;
    }
}

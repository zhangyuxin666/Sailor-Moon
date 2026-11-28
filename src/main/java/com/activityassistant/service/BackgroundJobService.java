package com.activityassistant.service;

import com.activityassistant.persistence.mapper.BackgroundJobMapper;
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
    private final BackgroundJobMapper jobs;
    private final AgentRunService agentRuns;
    @org.springframework.beans.factory.annotation.Value("${WORKER_LEASE_SECONDS:30}")
    private int leaseSeconds=30;
    @org.springframework.beans.factory.annotation.Value("${WORKER_MAX_SECONDS:600}")
    private int maxSeconds=600;
    @org.springframework.beans.factory.annotation.Value("${APP_INSTANCE_NAME:local}")
    private String instance="local";
    public int maxMillis(){return Math.max(2,maxSeconds)*1000;}
    public int heartbeatMillis(){return Math.max(250,leaseSeconds*1000/3);}

    public BackgroundJobService(AgentRunService agentRuns, BackgroundJobMapper jobs) {
        this.jobs = jobs;
        this.agentRuns = agentRuns;
    }

    @Transactional
    public Map<String, Object> enqueue(String kind, String refId) {
        Map<String, Object> old = jobs.findByRef(kind, refId).orElse(null);
        if (old != null) return old;
        String id = Ids.shortId();
        String now = LocalDateTime.now().toString();
        jobs.enqueue(id, kind, refId, now, now);
        return jobs.findByRef(kind, refId).orElseThrow();
    }

    @Transactional
    public Map<String, Object> claimNext() {
        String now = LocalDateTime.now().toString();
        Map<String, Object> row = jobs.lockNext(now).orElse(null);
        if (row == null) return null;
        String token=UUID.randomUUID().toString();
        jobs.claim(now,now,token,instance,now,Math.max(2,leaseSeconds),Math.max(2,maxSeconds),row.get("id"));
        Map<String,Object> claimed=new LinkedHashMap<>(row);
        claimed.put("attempts",((Number)row.get("attempts")).intValue()+1);
        claimed.put("worker_instance",instance);claimed.put("status","running");claimed.put("locked_by",token);
        claimed.put("lease_version",((Number)row.get("lease_version")).longValue()+1);
        return claimed;
    }

    public boolean heartbeat(Map<String,Object> job) {
        String now=LocalDateTime.now().toString();
        return jobs.heartbeat(now,Math.max(2,leaseSeconds),now,job.get("id"),job.get("locked_by"),job.get("lease_version"))==1;
    }
    public void finish(Map<String,Object> job) {
        JobLease.verify();
        String now=LocalDateTime.now().toString();
        jobs.finish(now,now,job.get("id"),job.get("locked_by"),job.get("lease_version"));
    }
    public void cancel(Map<String,Object> job) {
        jobs.cancel(LocalDateTime.now().toString(),job.get("id"),job.get("locked_by"),job.get("lease_version"));
    }
    @Transactional
    public String fail(Map<String,Object> job,Exception exception) {
        JobLease.verify();
        int attempts=((Number)job.get("attempts")).intValue();
        boolean retryable=!(exception instanceof ApiException e)||e.status().is5xxServerError();
        String status=retryable&&attempts<3?"queued":"failed";
        String now=LocalDateTime.now().toString(),error=PublicErrors.failure(exception);
        int changed=jobs.markFailure(status,error,"failed".equals(status)?now:null,"queued".equals(status)?LocalDateTime.now().plusSeconds(1L<<attempts).toString():null,now,job.get("id"),job.get("locked_by"),job.get("lease_version"));
        if(changed!=1)throw new JobLease.Lost();
        synchronize(job,status,error);
        return status;
    }
    @Transactional
    public void recover(Map<String,Object> candidate) {
        Map<String,Object> job=jobs.lockExpired(candidate.get("id")).orElse(null);
        if(job==null)return;
        String status=((Number)job.get("attempts")).intValue()<3?"queued":"failed";
        String now=LocalDateTime.now().toString(),error="执行租约到期或任务超时，已中止旧执行者";
        jobs.recover(status,error,now,"failed".equals(status)?now:null,now,job.get("id"));
        synchronize(job,status,error);
    }
    private void synchronize(Map<String,Object> job,String status,String error) {
        if("failed".equals(status)&&("reminder".equals(job.get("kind"))||"todo_reminder".equals(job.get("kind")))) {
            if ("reminder".equals(job.get("kind"))) {
                jobs.failReminder(error, job.get("ref_id"));
            } else {
                jobs.failTodoReminder(error, job.get("ref_id"));
            }
        }
        if("failed".equals(status)&&"activity_change".equals(job.get("kind")))jobs.failActivityChange(error,job.get("ref_id"));
        if("activity".equals(job.get("kind"))) {
            String runStatus="queued".equals(status)?"pending":"failed";
            jobs.updateActivityState(status,job.get("ref_id"));
            jobs.updateRunState(runStatus,error,LocalDateTime.now().toString(),job.get("ref_id"));
        }
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> job = jobs.findById(id).orElse(null);
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

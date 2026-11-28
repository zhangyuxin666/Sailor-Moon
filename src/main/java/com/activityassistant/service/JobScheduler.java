package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.persistence.mapper.JobSchedulerMapper;
import com.activityassistant.persistence.mapper.BackgroundJobMapper;
import com.activityassistant.support.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class JobScheduler {
    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);
    private final JobSchedulerMapper scheduler;
    private final BackgroundJobMapper jobMapper;
    private final BackgroundJobService jobs;
    private final ActivityOrchestrator orchestrator;
    private final AgentRunService agentRuns;
    private final ClassroomService classroom;
    private final QqService qq;
    private final AppProperties properties;
    private final ActivityGate gate;
    private final ChangeDeliveryService changes;

    public JobScheduler(JobSchedulerMapper scheduler, BackgroundJobService jobs, ActivityOrchestrator orchestrator, AgentRunService agentRuns,
                        ClassroomService classroom, QqService qq, AppProperties properties, ActivityGate gate, ChangeDeliveryService changes, BackgroundJobMapper jobMapper) {
        this.scheduler = scheduler;
        this.jobMapper = jobMapper;
        this.jobs = jobs;
        this.orchestrator = orchestrator;
        this.agentRuns = agentRuns;
        this.classroom = classroom;
        this.qq = qq;
        this.properties = properties;
        this.gate = gate;
        this.changes = changes;
    }

    private final java.util.concurrent.ScheduledExecutorService heartbeats=java.util.concurrent.Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"worker-heartbeat");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy
    public void stop(){heartbeats.shutdownNow();}
    @Scheduled(fixedDelayString = "${WORKER_POLL_MILLIS:1000}")
    public void work() {
        // 学习点: claimNext 从数据库队列抢一条任务；队列空时返回 null，这是正常情况。
        Map<String, Object> job = jobs.claimNext();
        if (job == null) return;
        var heartbeat=heartbeats.scheduleAtFixedRate(()->{try{jobs.heartbeat(job);}catch(Exception error){log.warn("Worker heartbeat unavailable for {}",job.get("id"));}},jobs.heartbeatMillis(),jobs.heartbeatMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
        Thread worker=Thread.currentThread();
        var timeout=heartbeats.schedule(worker::interrupt,jobs.maxMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
        try(JobLease lease=new JobLease(job, jobMapper)) {
            try {
                JobLease.verify();
                String ref=String.valueOf(job.get("ref_id"));
                switch(String.valueOf(job.get("kind"))) {
                    case "activity" -> orchestrator.run(ref);
                    case "reminder" -> deliverReminder(ref);
                    case "todo_reminder" -> {
                        var row=scheduler.findTodoReminder(ref).orElseThrow();
                        gate.todoDuring(String.valueOf(row.get("todo_id")),()->{deliverTodoReminder(ref);return null;});
                    }
                    case "activity_change" -> changes.deliver(ref);
                    default -> throw com.activityassistant.support.ApiException.badRequest("未知任务类型");
                }
                jobs.finish(job);
            } catch(ActivityGate.CancelledFlow cancelled) {jobs.cancel(job);}
            catch(JobLease.Lost lost){log.info("Execution fenced: {}",job.get("id"));}
            catch(Exception error) {
                try {
                    String activity=activityOf(job);
                    java.util.function.Supplier<String> fail=()->{
                        String state=jobs.fail(job,error);
                        if("activity_change".equals(job.get("kind")))changes.failed(String.valueOf(job.get("ref_id")),error,"queued".equals(state));
                        if("reminder".equals(job.get("kind"))||"todo_reminder".equals(job.get("kind"))) {
                            String publicError = com.activityassistant.support.PublicErrors.failure(error);
                            String terminalStatus = error instanceof com.activityassistant.support.ApiException e
                                    && e.status().value() == 409 ? "unknown" : "failed";
                            String id = String.valueOf(job.get("ref_id"));
                            if ("reminder".equals(job.get("kind"))) {
                                scheduler.recordReminderFailure(publicError, state, terminalStatus, id);
                            } else {
                                scheduler.recordTodoReminderFailure(publicError, state, terminalStatus, id);
                            }
                        }
                        return state;
                    };
                    if(activity==null)fail.get();else gate.settle(activity,fail);
                }catch(JobLease.Lost lost){log.info("Failure ignored for expired execution {}",job.get("id"));}
            }
        }finally{heartbeat.cancel(false);timeout.cancel(false);Thread.interrupted();}
    }
    private String activityOf(Map<String,Object> job) {
        String kind=String.valueOf(job.get("kind"));
        if("activity".equals(kind))return String.valueOf(job.get("ref_id"));
        String ref = String.valueOf(job.get("ref_id"));
        return switch (kind) {
            case "reminder" -> scheduler.findReminderActivity(ref).map(r -> String.valueOf(r.get("activity_id"))).orElse(null);
            case "activity_change" -> scheduler.findChangeActivity(ref).map(r -> String.valueOf(r.get("activity_id"))).orElse(null);
            default -> null;
        };
    }

    @Scheduled(fixedDelayString = "${REMINDER_SCAN_MILLIS:5000}")
    public void enqueueDueReminders() {
        Instant now = Instant.now();
        due(scheduler.listScheduledReminders(), now)
                .forEach(row -> jobs.enqueue("reminder", String.valueOf(row.get("id"))));
        due(scheduler.listScheduledTodoReminders(), now)
                .forEach(row -> jobs.enqueue("todo_reminder", String.valueOf(row.get("id"))));
    }

    @Scheduled(fixedDelayString="${WORKER_RECOVERY_MILLIS:5000}")
    public void recoverStaleJobs() {
        for(var job:scheduler.listExpiredJobs()) {
            String activity=activityOf(job);
            if(activity==null)jobs.recover(job);
            else gate.during(activity,true,()->{jobs.recover(job);return null;});
        }
    }

    private List<Map<String, Object>> due(List<Map<String, Object>> rows, Instant now) {
        return rows.stream().filter(row -> {
            try { return !Times.instant(String.valueOf(row.get("remind_at")), properties.timezone()).isAfter(now); }
            catch (Exception exception) { return false; }
        }).toList();
    }

    private void deliverReminder(String reminderId) {
        String activityId=scheduler.findReminderActivity(reminderId).map(r->String.valueOf(r.get("activity_id"))).orElse(null);
        if(activityId!=null)gate.during(activityId,false,()->{deliverReminderLocked(reminderId);return null;});
    }
    private void deliverReminderLocked(String reminderId) {
        Map<String, Object> reminder = scheduler.findScheduledReminder(reminderId).orElse(null);
        if (reminder == null) return;
        String activityId = String.valueOf(reminder.get("activity_id"));
        String key = "reminder:" + reminderId;
        if (scheduler.findReceipt(key).isEmpty()) {
            String group = scheduler.findActivityChannel(activityId)
                    .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
            Map<String, Object> result;
            if (group != null) result = orchestrator.sendGroupMessage(
                    activityId, group, String.valueOf(reminder.get("message")), "reminder", key);
            else {
                scheduler.insertMessage(activityId, (String) reminder.get("message"), LocalDateTime.now().toString());
                result = Map.of("status", "sent");
            }
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                scheduler.saveReminderReceipt(key, json, LocalDateTime.now().toString());
            } catch (Exception ignored) {}
        }
        scheduler.markReminderSent(reminderId);
    }

    private void deliverTodoReminder(String reminderId) {
        Map<String, Object> reminder = scheduler.findScheduledTodoReminder(reminderId).orElse(null);
        if (reminder == null) return;
        String key = "todo-reminder:" + reminderId;
        if (scheduler.findReceipt(key).isEmpty()) {
            Map<String, Object> result = classroom.remindMissing(null, String.valueOf(reminder.get("todo_id")), true,key);
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                scheduler.saveTodoReceipt(key, json, LocalDateTime.now().toString());
            } catch (Exception ignored) {}
        }
        scheduler.markTodoReminderSent(reminderId);
    }
}

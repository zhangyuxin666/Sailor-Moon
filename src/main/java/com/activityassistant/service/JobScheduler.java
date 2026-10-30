package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.orchestration.ActivityOrchestrator;
import com.activityassistant.persistence.Db;
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
    private final Db db;
    private final BackgroundJobService jobs;
    private final ActivityOrchestrator orchestrator;
    private final AgentRunService agentRuns;
    private final ClassroomService classroom;
    private final QqService qq;
    private final AppProperties properties;
    private final ActivityGate gate;
    private final ChangeDeliveryService changes;

    public JobScheduler(Db db, BackgroundJobService jobs, ActivityOrchestrator orchestrator, AgentRunService agentRuns,
                        ClassroomService classroom, QqService qq, AppProperties properties, ActivityGate gate, ChangeDeliveryService changes) {
        this.db = db;
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
        try(JobLease lease=new JobLease(job)) {
            try {
                JobLease.verify(db);
                String ref=String.valueOf(job.get("ref_id"));
                switch(String.valueOf(job.get("kind"))) {
                    case "activity" -> orchestrator.run(ref);
                    case "reminder" -> deliverReminder(ref);
                    case "todo_reminder" -> {
                        var row=db.required("SELECT todo_id FROM todo_reminders WHERE id=?",ref);
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
                            String table="reminder".equals(job.get("kind"))?"reminders":"todo_reminders";
                            db.update("UPDATE "+table+" SET error=?,status=CASE WHEN ?='failed' THEN ? ELSE status END WHERE id=? AND status IN ('scheduled','failed')",com.activityassistant.support.PublicErrors.failure(error),state,error instanceof com.activityassistant.support.ApiException e&&e.status().value()==409?"unknown":"failed",job.get("ref_id"));
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
        String table="reminder".equals(kind)?"reminders":"activity_change".equals(kind)?"activity_changes":null;
        return table==null?null:db.one("SELECT activity_id FROM "+table+" WHERE id=?",job.get("ref_id")).map(r->String.valueOf(r.get("activity_id"))).orElse(null);
    }

    @Scheduled(fixedDelayString = "${REMINDER_SCAN_MILLIS:5000}")
    public void enqueueDueReminders() {
        Instant now = Instant.now();
        due(db.list("SELECT id, remind_at FROM reminders WHERE status='scheduled'"), now)
                .forEach(row -> jobs.enqueue("reminder", String.valueOf(row.get("id"))));
        due(db.list("SELECT id, remind_at FROM todo_reminders WHERE status='scheduled'"), now)
                .forEach(row -> jobs.enqueue("todo_reminder", String.valueOf(row.get("id"))));
    }

    @Scheduled(fixedDelayString="${WORKER_RECOVERY_MILLIS:5000}")
    public void recoverStaleJobs() {
        for(var job:db.list("SELECT * FROM background_jobs WHERE status='running' AND (lease_until IS NULL OR lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp())")) {
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
        String activityId=db.one("SELECT activity_id FROM reminders WHERE id=?",reminderId).map(r->String.valueOf(r.get("activity_id"))).orElse(null);
        if(activityId!=null)gate.during(activityId,false,()->{deliverReminderLocked(reminderId);return null;});
    }
    private void deliverReminderLocked(String reminderId) {
        Map<String, Object> reminder = db.one("SELECT * FROM reminders WHERE id=? AND status='scheduled'", reminderId).orElse(null);
        if (reminder == null) return;
        String activityId = String.valueOf(reminder.get("activity_id"));
        String key = "reminder:" + reminderId;
        if (db.one("SELECT 1 FROM idempotency_keys WHERE key=?", key).isEmpty()) {
            String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", activityId)
                    .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
            Map<String, Object> result;
            if (group != null) result = orchestrator.sendGroupMessage(
                    activityId, group, String.valueOf(reminder.get("message")), "reminder", key);
            else {
                db.update("INSERT INTO messages (activity_id, recipient, content, created_at) VALUES (?, 'all', ?, ?)",
                        activityId, reminder.get("message"), LocalDateTime.now().toString());
                result = Map.of("status", "sent");
            }
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                db.update("INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, 'send_message', ?, ?) ON CONFLICT DO NOTHING",
                        key, json, LocalDateTime.now().toString());
            } catch (Exception ignored) {}
        }
        db.update("UPDATE reminders SET status='sent' WHERE id=?", reminderId);
    }

    private void deliverTodoReminder(String reminderId) {
        Map<String, Object> reminder = db.one("SELECT * FROM todo_reminders WHERE id=? AND status='scheduled'", reminderId).orElse(null);
        if (reminder == null) return;
        String key = "todo-reminder:" + reminderId;
        if (db.one("SELECT 1 FROM idempotency_keys WHERE key=?", key).isEmpty()) {
            Map<String, Object> result = classroom.remindMissing(null, String.valueOf(reminder.get("todo_id")), true,key);
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                db.update("INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, 'send_qq_group_message', ?, ?) ON CONFLICT DO NOTHING",
                        key, json, LocalDateTime.now().toString());
            } catch (Exception ignored) {}
        }
        db.update("UPDATE todo_reminders SET status='sent' WHERE id=?", reminderId);
    }
}

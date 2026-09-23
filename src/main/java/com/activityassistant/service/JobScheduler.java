package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
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
    private final ActivityService activities;
    private final AgentRunService agentRuns;
    private final ClassroomService classroom;
    private final QqService qq;
    private final AppProperties properties;

    public JobScheduler(Db db, BackgroundJobService jobs, ActivityService activities, AgentRunService agentRuns,
                        ClassroomService classroom, QqService qq, AppProperties properties) {
        this.db = db;
        this.jobs = jobs;
        this.activities = activities;
        this.agentRuns = agentRuns;
        this.classroom = classroom;
        this.qq = qq;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${WORKER_POLL_MILLIS:1000}")
    public void work() {
        Map<String, Object> job = jobs.claimNext();
        if (job == null) return;
        try {
            String kind = String.valueOf(job.get("kind"));
            String ref = String.valueOf(job.get("ref_id"));
            switch (kind) {
                case "activity" -> activities.run(ref);
                case "reminder" -> deliverReminder(ref);
                case "todo_reminder" -> deliverTodoReminder(ref);
                default -> throw new IllegalArgumentException("未知任务类型: " + kind);
            }
            jobs.finish(String.valueOf(job.get("id")));
        } catch (Exception exception) {
            log.error("后台任务失败: {}", job.get("id"), exception);
            String status = jobs.fail(job, exception);
            if ("activity".equals(job.get("kind")) && "queued".equals(status)) {
                db.update("UPDATE activities SET status='queued' WHERE id=?", job.get("ref_id"));
                agentRuns.retrying(String.valueOf(job.get("ref_id")), exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${REMINDER_SCAN_MILLIS:5000}")
    public void enqueueDueReminders() {
        Instant now = Instant.now();
        due(db.list("SELECT id, remind_at FROM reminders WHERE status='scheduled'"), now)
                .forEach(row -> jobs.enqueue("reminder", String.valueOf(row.get("id"))));
        due(db.list("SELECT id, remind_at FROM todo_reminders WHERE status='scheduled'"), now)
                .forEach(row -> jobs.enqueue("todo_reminder", String.valueOf(row.get("id"))));
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverStaleJobs() {
        String now = LocalDateTime.now().toString();
        db.update("UPDATE background_jobs SET status='queued', error='recovered stale running job', locked_by=NULL, " +
                "next_attempt_at=?, updated_at=? WHERE status='running' AND COALESCE(heartbeat_at, started_at)<?",
                now, now, LocalDateTime.now().minusMinutes(10).toString());
    }

    private List<Map<String, Object>> due(List<Map<String, Object>> rows, Instant now) {
        return rows.stream().filter(row -> {
            try { return !Times.instant(String.valueOf(row.get("remind_at")), properties.timezone()).isAfter(now); }
            catch (Exception exception) { return false; }
        }).toList();
    }

    private void deliverReminder(String reminderId) {
        Map<String, Object> reminder = db.one("SELECT * FROM reminders WHERE id=? AND status='scheduled'", reminderId).orElse(null);
        if (reminder == null) return;
        String activityId = String.valueOf(reminder.get("activity_id"));
        String key = "reminder:" + reminderId;
        if (db.one("SELECT 1 FROM idempotency_keys WHERE key=?", key).isEmpty()) {
            String group = db.one("SELECT group_openid FROM activity_channels WHERE activity_id=?", activityId)
                    .map(row -> String.valueOf(row.get("group_openid"))).orElse(null);
            Map<String, Object> result;
            if (group != null) result = qq.sendGroup(group, String.valueOf(reminder.get("message")), key);
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
            Map<String, Object> result = classroom.remindMissing(null, String.valueOf(reminder.get("todo_id")), true);
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                db.update("INSERT INTO idempotency_keys (key, tool_name, result_json, created_at) VALUES (?, 'send_qq_group_message', ?, ?) ON CONFLICT DO NOTHING",
                        key, json, LocalDateTime.now().toString());
            } catch (Exception ignored) {}
        }
        db.update("UPDATE todo_reminders SET status='sent' WHERE id=?", reminderId);
    }
}

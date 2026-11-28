package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface ActivityManagementMapper {
    @Update("""
            UPDATE activities
            SET status='cancelled',cancelled_at=#{now},cancel_reason=#{reason},version=version+1
            WHERE id=#{id}
            """)
    void cancelActivity(@Param("now") String now, @Param("reason") String reason, @Param("id") String id);

    @Update("""
            UPDATE agent_runs
            SET status='cancelled',cancelled_at=#{now},resolution_json=#{result},error=NULL,updated_at=#{updatedAt}
            WHERE kind='activity' AND ref_id=#{id} AND status NOT IN ('succeeded','cancelled')
            """)
    void cancelRun(@Param("now") String now, @Param("result") String result, @Param("updatedAt") String updatedAt, @Param("id") String id);

    @Update("""
            UPDATE background_jobs
            SET status='failed',error='活动已取消',finished_at=#{now},locked_by=NULL,heartbeat_at=NULL
            WHERE kind='activity' AND ref_id=#{id} AND status IN ('queued','running')
            """)
    void cancelActivityJobs(@Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE forms
            SET registration_status='closed',version=version+1
            WHERE activity_id=#{id}
            """)
    void closeForms(@Param("id") String id);

    @Update("""
            UPDATE participation_settings
            SET attendance_open=false,feedback_open=false,version=version+1
            WHERE activity_id=#{id}
            """)
    void closeParticipation(@Param("id") String id);

    @Update("""
            UPDATE background_jobs
            SET status='failed',error='活动已取消',finished_at=#{now},locked_by=NULL,heartbeat_at=NULL
            WHERE kind='reminder' AND ref_id IN (SELECT id
            FROM reminders
            WHERE activity_id=#{id}) AND status IN ('queued','running')
            """)
    void cancelReminderJobs(@Param("now") String now, @Param("id") String id);

    @Update("""
            UPDATE reminders
            SET status='cancelled'
            WHERE activity_id=#{id} AND status='scheduled'
            """)
    void cancelReminders(@Param("id") String id);

    @Select("""
            SELECT registration_deadline
            FROM forms
            WHERE activity_id=#{id}
            """)
    List<Map<String, Object>> listDeadlines(@Param("id") String id);

    @Update("""
            UPDATE activities
            SET plan_json=#{plan},version=#{version}
            WHERE id=#{id}
            """)
    void rescheduleActivity(@Param("plan") String plan, @Param("version") int version, @Param("id") String id);

    @Select("""
            SELECT id,resolution_json,result_json
            FROM agent_runs
            WHERE kind='activity' AND ref_id=#{id}
            """)
    List<Map<String, Object>> listRuns(@Param("id") String id);

    @Update("""
            UPDATE agent_runs
            SET plan_json=#{plan},resolution_json=#{resolution},result_json=#{result},updated_at=#{now}
            WHERE id=#{id}
            """)
    void updateRunPlan(@Param("plan") String plan, @Param("resolution") String resolution, @Param("result") String result, @Param("now") String now, @Param("id") Object id);

    @Update("""
            UPDATE background_jobs
            SET status='failed',error='活动已改期，旧提醒失效',locked_by=NULL,heartbeat_at=NULL
            WHERE kind='reminder' AND ref_id IN (SELECT id
            FROM reminders
            WHERE activity_id=#{id} AND status='scheduled') AND status IN ('queued','running')
            """)
    void invalidateReminderJobs(@Param("id") String id);

    @Select("""
            SELECT *
            FROM reminders
            WHERE activity_id=#{id} AND status='scheduled'
            """)
    List<Map<String, Object>> listScheduledReminders(@Param("id") String id);

    @Insert("""
            INSERT INTO reminders(id,activity_id,message,remind_at,status,created_at)
            VALUES (#{reminderId},#{id},#{message},#{remindAt},'scheduled',#{createdAt}) ON CONFLICT(activity_id,remind_at) DO UPDATE
            SET status='scheduled',message=EXCLUDED.message
            """)
    void upsertReminder(@Param("reminderId") String reminderId, @Param("id") String id, @Param("message") Object message, @Param("remindAt") String remindAt, @Param("createdAt") String createdAt);

    @Update("""
            UPDATE background_jobs
            SET status='queued',attempts=0,error=NULL,finished_at=NULL,next_attempt_at=#{nextAttempt}
            WHERE kind='reminder' AND ref_id IN (SELECT id
            FROM reminders
            WHERE activity_id=#{id} AND remind_at=#{remindAt} AND status='scheduled')
            """)
    void requeueReminderJob(@Param("nextAttempt") String nextAttempt, @Param("id") String id, @Param("remindAt") String remindAt);

    @Update("""
            UPDATE forms
            SET registration_deadline=#{deadline},version=version+1
            WHERE activity_id=#{id}
            """)
    void setDeadline(@Param("deadline") String deadline, @Param("id") String id);

    @Update("""
            UPDATE forms
            SET registration_deadline=NULL,version=version+1
            WHERE activity_id=#{id}
            """)
    void clearDeadline(@Param("id") String id);

    @Select("""
            SELECT result_json
            FROM idempotency_keys
            WHERE key=#{key} AND tool_name='create_calendar_event'
            """)
    Map<String, Object> findCalendarRow(@Param("key") String key);

    default Optional<Map<String, Object>> findCalendar(String key) {
        return Optional.ofNullable(findCalendarRow(key));
    }

    @Update("""
            UPDATE idempotency_keys
            SET result_json=#{result}
            WHERE key=#{key} AND tool_name='create_calendar_event'
            """)
    void updateCalendar(@Param("result") String result, @Param("key") String key);

    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{id}
            """)
    Map<String, Object> findActivityRow(@Param("id") String id);

    default Optional<Map<String, Object>> findActivity(String id) {
        return Optional.ofNullable(findActivityRow(id));
    }

    @Select("""
            SELECT id,kind,result_json,content,notification_status,error,created_at,completed_at
            FROM activity_changes
            WHERE activity_id=#{id}
            ORDER BY created_at DESC,id
            """)
    List<Map<String, Object>> listChanges(@Param("id") String id);

    @Select("""
            SELECT *
            FROM activity_changes
            WHERE id=#{changeId} AND activity_id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockChangeRow(@Param("changeId") String changeId, @Param("id") String id);

    default Optional<Map<String, Object>> lockChange(String changeId, String id) {
        return Optional.ofNullable(lockChangeRow(changeId, id));
    }

    @Update("""
            UPDATE background_jobs
            SET status='queued',attempts=0,error=NULL,finished_at=NULL,next_attempt_at=NULL
            WHERE kind='activity_change' AND ref_id=#{changeId} AND status<>'running'
            """)
    void requeueChangeJob(@Param("changeId") String changeId);

    @Update("""
            UPDATE activity_changes
            SET notification_status='pending',error=NULL
            WHERE id=#{changeId}
            """)
    void markChangePending(@Param("changeId") String changeId);
}

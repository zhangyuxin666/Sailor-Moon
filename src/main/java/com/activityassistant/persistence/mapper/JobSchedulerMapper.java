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
public interface JobSchedulerMapper {
    @Select("""
            SELECT todo_id
            FROM todo_reminders
            WHERE id=#{id}
            """)
    Map<String, Object> findTodoReminderRow(@Param("id") String id);

    default Optional<Map<String, Object>> findTodoReminder(String id) {
        return Optional.ofNullable(findTodoReminderRow(id));
    }

    @Select("""
            SELECT id, remind_at
            FROM reminders
            WHERE status='scheduled'
            """)
    List<Map<String, Object>> listScheduledReminders();

    @Select("""
            SELECT id, remind_at
            FROM todo_reminders
            WHERE status='scheduled'
            """)
    List<Map<String, Object>> listScheduledTodoReminders();

    @Select("""
            SELECT *
            FROM background_jobs
            WHERE status='running' AND (lease_until IS NULL OR lease_until<=clock_timestamp() OR deadline_at<=clock_timestamp())
            """)
    List<Map<String, Object>> listExpiredJobs();

    @Select("""
            SELECT activity_id
            FROM reminders
            WHERE id=#{id}
            """)
    Map<String, Object> findReminderActivityRow(@Param("id") String id);

    default Optional<Map<String, Object>> findReminderActivity(String id) {
        return Optional.ofNullable(findReminderActivityRow(id));
    }

    @Select("""
            SELECT *
            FROM reminders
            WHERE id=#{id} AND status='scheduled'
            """)
    Map<String, Object> findScheduledReminderRow(@Param("id") String id);

    default Optional<Map<String, Object>> findScheduledReminder(String id) {
        return Optional.ofNullable(findScheduledReminderRow(id));
    }

    @Select("""
            SELECT 1
            FROM idempotency_keys
            WHERE key=#{key}
            """)
    Map<String, Object> findReceiptRow(@Param("key") String key);

    default Optional<Map<String, Object>> findReceipt(String key) {
        return Optional.ofNullable(findReceiptRow(key));
    }

    @Select("""
            SELECT group_openid
            FROM activity_channels
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findActivityChannelRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityChannel(String activityId) {
        return Optional.ofNullable(findActivityChannelRow(activityId));
    }

    @Insert("""
            INSERT INTO messages (activity_id, recipient, content, created_at)
            VALUES (#{activityId}, 'all', #{content}, #{createdAt})
            """)
    void insertMessage(@Param("activityId") String activityId, @Param("content") String content, @Param("createdAt") String createdAt);

    @Insert("""
            INSERT INTO idempotency_keys (key, tool_name, result_json, created_at)
            VALUES (#{key}, 'send_message', #{result}, #{createdAt}) ON CONFLICT DO NOTHING
            """)
    void saveReminderReceipt(@Param("key") String key, @Param("result") String result, @Param("createdAt") String createdAt);

    @Update("""
            UPDATE reminders
            SET status='sent'
            WHERE id=#{id}
            """)
    void markReminderSent(@Param("id") String id);

    @Select("""
            SELECT *
            FROM todo_reminders
            WHERE id=#{id} AND status='scheduled'
            """)
    Map<String, Object> findScheduledTodoReminderRow(@Param("id") String id);

    default Optional<Map<String, Object>> findScheduledTodoReminder(String id) {
        return Optional.ofNullable(findScheduledTodoReminderRow(id));
    }

    @Insert("""
            INSERT INTO idempotency_keys (key, tool_name, result_json, created_at)
            VALUES (#{key}, 'send_qq_group_message', #{result}, #{createdAt}) ON CONFLICT DO NOTHING
            """)
    void saveTodoReceipt(@Param("key") String key, @Param("result") String result, @Param("createdAt") String createdAt);

    @Update("""
            UPDATE todo_reminders
            SET status='sent'
            WHERE id=#{id}
            """)
    void markTodoReminderSent(@Param("id") String id);

    @Update("""
            UPDATE reminders
            SET error=#{error}, status=CASE WHEN #{state}='failed' THEN #{terminalStatus} ELSE status END
            WHERE id=#{id} AND status IN ('scheduled','failed')
            """)
    void recordReminderFailure(@Param("error") String error, @Param("state") String state, @Param("terminalStatus") String terminalStatus, @Param("id") String id);

    @Update("""
            UPDATE todo_reminders
            SET error=#{error}, status=CASE WHEN #{state}='failed' THEN #{terminalStatus} ELSE status END
            WHERE id=#{id} AND status IN ('scheduled','failed')
            """)
    void recordTodoReminderFailure(@Param("error") String error, @Param("state") String state, @Param("terminalStatus") String terminalStatus, @Param("id") String id);

    @Select("""
            SELECT activity_id
            FROM activity_changes
            WHERE id=#{id}
            """)
    Map<String, Object> findChangeActivityRow(@Param("id") String id);

    default Optional<Map<String, Object>> findChangeActivity(String id) {
        return Optional.ofNullable(findChangeActivityRow(id));
    }
}

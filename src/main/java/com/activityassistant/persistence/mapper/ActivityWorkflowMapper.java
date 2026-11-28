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
public interface ActivityWorkflowMapper {
    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivityRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivity(String activityId) {
        return Optional.ofNullable(findActivityRow(activityId));
    }

    @Update("""
            UPDATE activities
            SET status='running'
            WHERE id=#{activityId}
            """)
    void markRunning(@Param("activityId") String activityId);

    @Update("""
            UPDATE activities
            SET status='ready'
            WHERE id=#{activityId}
            """)
    void markReady(@Param("activityId") String activityId);

    @Update("""
            UPDATE activities
            SET status='failed'
            WHERE id=#{activityId}
            """)
    void markFailed(@Param("activityId") String activityId);

    @Select("""
            SELECT class_id, organization_id
            FROM activity_classes
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findScopeRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findScope(String activityId) {
        return Optional.ofNullable(findScopeRow(activityId));
    }

    @Select("""
            SELECT a.id AS account_id, a.display_name
            FROM class_members cm JOIN accounts a ON a.id=cm.account_id
            WHERE cm.class_id=#{classId} AND a.status='active'
            ORDER BY a.student_no, a.id
            """)
    List<Map<String, Object>> listAssignees(@Param("classId") String classId);

    @Update("""
            UPDATE activities
            SET title=#{title}, plan_json=#{plan}
            WHERE id=#{activityId}
            """)
    void savePlan(@Param("title") String title, @Param("plan") String plan, @Param("activityId") String activityId);

    @Update("""
            UPDATE activities
            SET status='waiting_input'
            WHERE id=#{activityId}
            """)
    void markWaiting(@Param("activityId") String activityId);

    @Insert("""
            INSERT INTO forms (id, activity_id, title, fields_json, created_at)
            VALUES (#{id}, #{activityId}, #{title}, #{fields}, #{now})
            """)
    void insertForm(@Param("id") String id, @Param("activityId") String activityId, @Param("title") String title, @Param("fields") String fields, @Param("now") String now);

    @Select("""
            SELECT *
            FROM tasks
            WHERE activity_id=#{activityId} AND plan_key=#{planKey}
            """)
    Map<String, Object> findTaskRow(@Param("activityId") String activityId, @Param("planKey") String planKey);

    default Optional<Map<String, Object>> findTask(String activityId, String planKey) {
        return Optional.ofNullable(findTaskRow(activityId, planKey));
    }

    @Select("""
            SELECT t.id
            FROM tasks t JOIN activity_task_assignees ata ON ata.task_id=t.id
            WHERE t.activity_id=#{activityId} AND t.title=#{title} AND ata.account_id=#{accountId} AND t.plan_key LIKE 'legacy:%'
            """)
    List<Map<String, Object>> listLegacyTasks(@Param("activityId") String activityId, @Param("title") String title, @Param("accountId") String accountId);

    @Insert("""
            INSERT INTO tasks (id, activity_id, title, assignee, status, created_at, description, deadline, priority, plan_key, unassigned_reason)
            VALUES (#{id}, #{activityId}, #{title}, #{assignee}, 'pending', #{now}, #{description}, #{deadline}, #{priority}, #{planKey}, #{reason})
            """)
    void insertTask(@Param("id") String id, @Param("activityId") String activityId, @Param("title") String title, @Param("assignee") String assignee, @Param("now") String now, @Param("description") String description, @Param("deadline") String deadline, @Param("priority") String priority, @Param("planKey") String planKey, @Param("reason") String reason);

    @Insert("""
            INSERT INTO activity_task_assignees (task_id, account_id)
            VALUES (#{id}, #{accountId})
            """)
    void assignTask(@Param("id") String id, @Param("accountId") String accountId);

    @Update("""
            UPDATE tasks
            SET plan_key=#{planKey}
            WHERE id=#{id} AND (plan_key IS NULL OR plan_key LIKE 'legacy:%')
            """)
    void linkPlanKey(@Param("planKey") String planKey, @Param("id") String id);

    @Insert("""
            INSERT INTO messages (activity_id, recipient, content, created_at, recipient_account_id)
            VALUES (#{id}, #{recipient}, #{content}, #{now}, #{accountId})
            """)
    void insertMessage(@Param("id") String id, @Param("recipient") String recipient, @Param("content") String content, @Param("now") String now, @Param("accountId") String accountId);

    @Insert("""
            INSERT INTO task_dependencies(task_id,depends_on_id)
            VALUES (#{taskId},#{dependencyId})
            """)
    void insertDependency(@Param("taskId") String taskId, @Param("dependencyId") String dependencyId);

    @Select("""
            SELECT *
            FROM reminders
            WHERE activity_id=#{activityId} AND remind_at=#{time}
            """)
    Map<String, Object> findReminderRow(@Param("activityId") String activityId, @Param("time") String time);

    default Optional<Map<String, Object>> findReminder(String activityId, String time) {
        return Optional.ofNullable(findReminderRow(activityId, time));
    }

    @Insert("""
            INSERT INTO reminders (id, activity_id, message, remind_at, status, created_at)
            VALUES (#{id}, #{activityId}, #{message}, #{time}, 'scheduled', #{now})
            """)
    void insertReminder(@Param("id") String id, @Param("activityId") String activityId, @Param("message") String message, @Param("time") String time, @Param("now") String now);

    @Select("""
            SELECT group_openid
            FROM activity_channels
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findChannelRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findChannel(String activityId) {
        return Optional.ofNullable(findChannelRow(activityId));
    }

    @Select("""
            SELECT target,content
            FROM delivery_events
            WHERE logical_key=#{key}
            """)
    Map<String, Object> findOriginalDeliveryRow(@Param("key") String key);

    default Optional<Map<String, Object>> findOriginalDelivery(String key) {
        return Optional.ofNullable(findOriginalDeliveryRow(key));
    }

    @Select("""
            SELECT organization_id
            FROM activity_classes
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findOrganizationRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findOrganization(String activityId) {
        return Optional.ofNullable(findOrganizationRow(activityId));
    }

    @Select("""
            SELECT DISTINCT ata.account_id
            FROM tasks t JOIN activity_task_assignees ata ON ata.task_id=t.id
            WHERE t.activity_id=#{activityId} AND t.title=#{title} AND t.assignee=#{assignee}
            """)
    List<Map<String, Object>> listPriorAssignees(@Param("activityId") String activityId, @Param("title") String title, @Param("assignee") String assignee);

    @Select("""
            SELECT config_json
            FROM activity_workflows
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findWorkflowRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findWorkflow(String activityId) {
        return Optional.ofNullable(findWorkflowRow(activityId));
    }
}

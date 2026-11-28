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
public interface ActivityMapper {
    @Insert("""
            INSERT INTO activities (id, user_id, title, raw_input, status, created_at)
            VALUES (#{id}, #{username}, #{title}, #{input}, 'queued', #{now})
            """)
    void insertActivity(@Param("id") String id, @Param("username") String username, @Param("title") String title, @Param("input") String input, @Param("now") String now);

    @Select("""
            SELECT c.qq_group_openid, co.organization_id
            FROM classes c JOIN class_organizations co ON co.class_id=c.id
            WHERE c.id=#{classId}
            """)
    Map<String, Object> findClassRow(@Param("classId") String classId);

    default Optional<Map<String, Object>> findClass(String classId) {
        return Optional.ofNullable(findClassRow(classId));
    }

    @Insert("""
            INSERT INTO activity_classes (activity_id, class_id, organization_id)
            VALUES (#{id}, #{classId}, #{organizationId})
            """)
    void linkClass(@Param("id") String id, @Param("classId") String classId, @Param("organizationId") Object organizationId);

    @Insert("""
            INSERT INTO activity_workflows (activity_id, config_json)
            VALUES (#{id}, #{config})
            """)
    void insertWorkflow(@Param("id") String id, @Param("config") String config);

    @Insert("""
            INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at)
            VALUES (#{id}, #{username}, #{group}, #{now})
            """)
    void insertChannel(@Param("id") String id, @Param("username") String username, @Param("group") String group, @Param("now") String now);

    @Select("""
            SELECT DISTINCT a.*, ac.class_id
            FROM activities a JOIN activity_classes ac ON ac.activity_id=a.id JOIN class_organizations co ON co.class_id=ac.class_id JOIN organization_members om ON om.organization_id=co.organization_id
            WHERE om.account_id=#{accountId} AND om.role IN ('owner','manager') AND a.user_id=#{username}
            ORDER BY a.created_at DESC
            """)
    List<Map<String, Object>> listManaged(@Param("accountId") String accountId, @Param("username") String username);

    @Select("""
            SELECT DISTINCT a.id, a.title, a.status, a.created_at
            FROM activities a JOIN tasks t ON t.activity_id=a.id JOIN activity_task_assignees ata ON ata.task_id=t.id
            WHERE ata.account_id=#{accountId}
            ORDER BY a.created_at DESC
            """)
    List<Map<String, Object>> listAssigned(@Param("accountId") String accountId);

    @Select("""
            SELECT user_id
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findOwnerRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findOwner(String activityId) {
        return Optional.ofNullable(findOwnerRow(activityId));
    }

    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivity(@Param("activityId") String activityId);

    @Select("""
            SELECT *
            FROM forms
            WHERE activity_id=#{activityId}
            """)
    List<Map<String, Object>> listForms(@Param("activityId") String activityId);

    @Select("""
            SELECT *
            FROM reminders
            WHERE activity_id=#{activityId}
            """)
    List<Map<String, Object>> listReminders(@Param("activityId") String activityId);

    @Select("""
            SELECT *
            FROM steps
            WHERE activity_id=#{activityId}
            ORDER BY id
            """)
    List<Map<String, Object>> listSteps(@Param("activityId") String activityId);

    @Select("""
            SELECT 1
            FROM activity_channels
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findChannelRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findChannel(String activityId) {
        return Optional.ofNullable(findChannelRow(activityId));
    }

    @Select("""
            SELECT *
            FROM delivery_events
            WHERE activity_id=#{activityId}
            ORDER BY created_at DESC
            """)
    List<Map<String, Object>> listDeliveries(@Param("activityId") String activityId);

    @Select("""
            SELECT activity_id
            FROM reminders
            WHERE id=#{reminderId}
            """)
    Map<String, Object> findReminderActivityRow(@Param("reminderId") String reminderId);

    default Optional<Map<String, Object>> findReminderActivity(String reminderId) {
        return Optional.ofNullable(findReminderActivityRow(reminderId));
    }

    @Select("""
            SELECT status
            FROM reminders
            WHERE id=#{reminderId} FOR UPDATE
            """)
    Map<String, Object> lockReminder(@Param("reminderId") String reminderId);

    @Update("""
            UPDATE reminders
            SET status='cancelled'
            WHERE id=#{reminderId}
            """)
    void cancelReminder(@Param("reminderId") String reminderId);

    @Update("""
            UPDATE background_jobs
            SET status='failed',error='提醒已取消',locked_by=NULL,lease_until=NULL,lease_version=lease_version+1
            WHERE kind='reminder' AND ref_id=#{reminderId} AND status IN ('queued','running')
            """)
    void cancelReminderJob(@Param("reminderId") String reminderId);

    @Select("""
            SELECT group_openid
            FROM activity_channels
            WHERE activity_id=#{activityId}
            """)
    Map<String, Object> findChannelGroupRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findChannelGroup(String activityId) {
        return Optional.ofNullable(findChannelGroupRow(activityId));
    }
}

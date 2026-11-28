package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface TaskManagementMapper {
    @Select("""
            SELECT a.id AS account_id,a.display_name,a.username,a.student_no,COUNT(t.id) FILTER (WHERE t.status='pending' AND ev.status NOT IN ('cancelled','finished')) AS pending_count
            FROM activity_classes ac JOIN class_members cm ON cm.class_id=ac.class_id JOIN accounts a ON a.id=cm.account_id
            LEFT JOIN activity_task_assignees ata ON ata.account_id=a.id
            LEFT JOIN tasks t ON t.id=ata.task_id
            LEFT JOIN activities ev ON ev.id=t.activity_id
            WHERE ac.activity_id=#{activityId} AND a.status='active' GROUP BY a.id,a.display_name,a.username,a.student_no
            ORDER BY pending_count,a.display_name,a.id
            """)
    List<Map<String, Object>> listMembers(@Param("activityId") String activityId);

    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{activityId}
            """)
    Map<String, Object> findActivityRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivity(String activityId) {
        return Optional.ofNullable(findActivityRow(activityId));
    }

    @Select("""
            SELECT t.*,ata.account_id,COALESCE(a.display_name,t.assignee) AS assignee
            FROM tasks t
            LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id
            LEFT JOIN accounts a ON a.id=ata.account_id
            WHERE t.activity_id=#{activityId}
            ORDER BY t.created_at,t.id
            """)
    List<Map<String, Object>> listTasks(@Param("activityId") String activityId);

    @Select("""
            SELECT depends_on_id
            FROM task_dependencies
            WHERE task_id=#{taskId}
            ORDER BY depends_on_id
            """)
    List<Map<String, Object>> listDependencies(@Param("taskId") Object taskId);

    @Select("""
            SELECT t.*,ata.account_id
            FROM tasks t
            LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id
            WHERE t.id=#{taskId} AND t.activity_id=#{activityId}
            """)
    Map<String, Object> findInActivityRow(@Param("taskId") String taskId, @Param("activityId") String activityId);

    default Optional<Map<String, Object>> findInActivity(String taskId, String activityId) {
        return Optional.ofNullable(findInActivityRow(taskId, activityId));
    }

    @Select("""
            SELECT status
            FROM tasks
            WHERE id=#{taskId}
            """)
    Map<String, Object> findStatusRow(@Param("taskId") String taskId);

    default Optional<Map<String, Object>> findStatus(String taskId) {
        return Optional.ofNullable(findStatusRow(taskId));
    }

    @Insert("""
            INSERT INTO tasks(id,activity_id,title,assignee,status,created_at,description,deadline,priority,unassigned_reason)
            VALUES (#{id},#{activityId},#{title},#{assignee},#{status},#{createdAt},#{description},#{deadline},#{priority},#{reason})
            """)
    void insertTask(@Param("id") String id, @Param("activityId") String activityId, @Param("title") String title, @Param("assignee") String assignee, @Param("status") String status, @Param("createdAt") String createdAt, @Param("description") String description, @Param("deadline") String deadline, @Param("priority") String priority, @Param("reason") String reason);

    @Update("""
            UPDATE tasks
            SET title=#{title},assignee=#{assignee},description=#{description},deadline=#{deadline},priority=#{priority},status=#{status},unassigned_reason=#{reason},version=version+1
            WHERE id=#{id}
            """)
    void updateTask(@Param("title") String title, @Param("assignee") String assignee, @Param("description") String description, @Param("deadline") String deadline, @Param("priority") String priority, @Param("status") String status, @Param("reason") String reason, @Param("id") String id);

    @Delete("""
            DELETE
            FROM activity_task_assignees
            WHERE task_id=#{taskId}
            """)
    void clearAssignees(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO activity_task_assignees(task_id,account_id)
            VALUES (#{taskId},#{accountId})
            """)
    void assign(@Param("taskId") String taskId, @Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM task_dependencies
            WHERE task_id=#{taskId}
            """)
    void clearDependencies(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO task_dependencies(task_id,depends_on_id)
            VALUES (#{taskId},#{dependencyId})
            """)
    void addDependency(@Param("taskId") String taskId, @Param("dependencyId") String dependencyId);

    @Select("""
            SELECT *
            FROM tasks
            WHERE id=#{taskId}
            """)
    Map<String, Object> findTaskRow(@Param("taskId") String taskId);

    default Optional<Map<String, Object>> findTask(String taskId) {
        return Optional.ofNullable(findTaskRow(taskId));
    }

    @Select("""
            SELECT 1
            FROM activity_task_assignees
            WHERE task_id=#{taskId} AND account_id=#{accountId}
            """)
    Map<String, Object> findAssignmentRow(@Param("taskId") String taskId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findAssignment(String taskId, String accountId) {
        return Optional.ofNullable(findAssignmentRow(taskId, accountId));
    }

    @Select("""
            SELECT t.id
            FROM task_dependencies d JOIN tasks t ON t.id=d.depends_on_id
            WHERE d.task_id=#{taskId} AND t.status<>'done'
            """)
    List<Map<String, Object>> listUnfinishedPrerequisites(@Param("taskId") String taskId);

    @Select("""
            SELECT t.id
            FROM task_dependencies d JOIN tasks t ON t.id=d.task_id
            WHERE d.depends_on_id=#{taskId} AND t.status='done'
            """)
    List<Map<String, Object>> listCompletedDependents(@Param("taskId") String taskId);

    @Update("""
            UPDATE tasks
            SET status=#{status},version=version+1
            WHERE id=#{taskId}
            """)
    void updateStatus(@Param("status") String status, @Param("taskId") String taskId);
}

package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface ExportMapper {
    @Select("""
            SELECT a.user_id,ac.class_id,ac.organization_id
            FROM activities a
            LEFT JOIN activity_classes ac ON ac.activity_id=a.id
            WHERE a.id=#{activityId}
            """)
    Map<String, Object> findActivityOwnerRow(@Param("activityId") String activityId);

    default Optional<Map<String, Object>> findActivityOwner(String activityId) {
        return Optional.ofNullable(findActivityOwnerRow(activityId));
    }

    @Select("""
            SELECT id,title,fields_json
            FROM forms
            WHERE activity_id=#{activityId}
            ORDER BY created_at,id
            """)
    List<Map<String, Object>> listForms(@Param("activityId") String activityId);

    @Select("""
            SELECT r.id,r.form_id,f.title AS form_title,r.name,r.contact,r.status,r.created_at,r.cancelled_at,r.extra_json
            FROM registrations r
            JOIN forms f ON f.id=r.form_id
            WHERE f.activity_id=#{activityId}
            ORDER BY r.created_at,r.id
            """)
    List<Map<String, Object>> listRegistrations(@Param("activityId") String activityId);

    @Select("""
            SELECT t.id,t.title,t.description,t.deadline,t.priority,t.status,t.assignee AS historical_assignee,t.unassigned_reason,ata.account_id,a.username,a.student_no,a.display_name
            FROM tasks t
            LEFT JOIN activity_task_assignees ata ON ata.task_id=t.id
            LEFT JOIN accounts a ON a.id=ata.account_id
            WHERE t.activity_id=#{activityId}
            ORDER BY t.created_at,t.id
            """)
    List<Map<String, Object>> listTasks(@Param("activityId") String activityId);

    @Select("""
            SELECT d.task_id,d.depends_on_id,dep.title
            FROM task_dependencies d
            JOIN tasks own ON own.id=d.task_id
            LEFT JOIN tasks dep ON dep.id=d.depends_on_id AND dep.activity_id=own.activity_id
            WHERE own.activity_id=#{activityId}
            ORDER BY d.task_id,d.depends_on_id
            """)
    List<Map<String, Object>> listDependencies(@Param("activityId") String activityId);

    @Select("""
            SELECT id,class_id,title,deadline
            FROM todos
            WHERE id=#{todoId}
            """)
    Map<String, Object> findTodoRow(@Param("todoId") String todoId);

    default Optional<Map<String, Object>> findTodo(String todoId) {
        return Optional.ofNullable(findTodoRow(todoId));
    }

    @Select("""
            SELECT ta.account_id,a.username,a.student_no,a.display_name,ta.status
            FROM todo_assignees ta
            JOIN accounts a ON a.id=ta.account_id
            WHERE ta.todo_id=#{todoId} AND ta.status<>'done'
            ORDER BY a.student_no,a.username,a.id
            """)
    List<Map<String, Object>> listMissingMembers(@Param("todoId") String todoId);
}

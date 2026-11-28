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
public interface ClassroomMapper {
    @Insert("""
            INSERT INTO classes (id, name, manager_id, qq_group_openid, created_at)
            VALUES (#{id}, #{name}, #{managerId}, #{groupId}, #{now})
            """)
    void insertClass(@Param("id") String id, @Param("name") String name, @Param("managerId") String managerId, @Param("groupId") String groupId, @Param("now") String now);

    @Insert("""
            INSERT INTO class_organizations (class_id, organization_id)
            VALUES (#{classId}, #{organizationId})
            """)
    void linkOrganization(@Param("classId") String classId, @Param("organizationId") String organizationId);

    @Select("""
            SELECT *
            FROM classes
            WHERE id=#{classId}
            """)
    Map<String, Object> findClassRow(@Param("classId") String classId);

    default Optional<Map<String, Object>> findClass(String classId) {
        return Optional.ofNullable(findClassRow(classId));
    }

    @Select("""
            SELECT co.organization_id, om.role
            FROM class_organizations co JOIN organization_members om ON om.organization_id=co.organization_id
            WHERE co.class_id=#{classId} AND om.account_id=#{accountId}
            """)
    Map<String, Object> findMembershipRow(@Param("classId") String classId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findMembership(String classId, String accountId) {
        return Optional.ofNullable(findMembershipRow(classId, accountId));
    }

    @Update("""
            UPDATE classes
            SET qq_group_openid=#{groupId}
            WHERE id=#{classId}
            """)
    void updateGroup(@Param("groupId") String groupId, @Param("classId") String classId);

    @Select("""
            SELECT *
            FROM accounts
            WHERE username=#{username} FOR UPDATE
            """)
    Map<String, Object> lockAccountRow(@Param("username") String username);

    default Optional<Map<String, Object>> lockAccount(String username) {
        return Optional.ofNullable(lockAccountRow(username));
    }

    @Select("""
            SELECT organization_id
            FROM organization_members
            WHERE account_id=#{accountId}
            """)
    List<Map<String, Object>> listOrganizations(@Param("accountId") String accountId);

    @Insert("""
            INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at)
            VALUES (#{accountId}, 1, 0, #{now})
            """)
    void initializeSecurity(@Param("accountId") String accountId, @Param("now") String now);

    @Insert("""
            INSERT INTO organization_members (organization_id, account_id, role, created_at)
            VALUES (#{organizationId}, #{accountId}, 'participant', #{now})
            """)
    void addParticipant(@Param("organizationId") String organizationId, @Param("accountId") String accountId, @Param("now") String now);

    @Insert("""
            INSERT INTO class_members (class_id, account_id, created_at)
            VALUES (#{classId}, #{accountId}, #{now})
            ON CONFLICT DO NOTHING
            """)
    void addClassMember(@Param("classId") String classId, @Param("accountId") String accountId, @Param("now") String now);

    @Insert("""
            INSERT INTO todos (id, class_id, creator_id, title, description, kind, deadline, status, created_at)
            VALUES (#{id}, #{classId}, #{creatorId}, #{title}, #{description}, #{kind}, #{deadline}, 'open', #{now})
            """)
    void insertTodo(@Param("id") String id, @Param("classId") String classId, @Param("creatorId") String creatorId, @Param("title") String title, @Param("description") String description, @Param("kind") String kind, @Param("deadline") String deadline, @Param("now") String now);

    @Select("""
            SELECT account_id
            FROM class_members
            WHERE class_id=#{classId}
            """)
    List<Map<String, Object>> listClassAccounts(@Param("classId") String classId);

    @Insert("""
            INSERT INTO todo_assignees (todo_id, account_id, status, updated_at)
            VALUES (#{todoId}, #{accountId}, 'pending', #{now})
            """)
    void assignTodo(@Param("todoId") Object todoId, @Param("accountId") Object accountId, @Param("now") String now);

    @Select("""
            SELECT c.*
            FROM classes c JOIN class_organizations co ON co.class_id=c.id JOIN organization_members om ON om.organization_id=co.organization_id
            WHERE om.account_id=#{accountId} AND om.role IN ('owner','manager')
            ORDER BY c.created_at DESC
            """)
    List<Map<String, Object>> listManagedClasses(@Param("accountId") String accountId);

    @Select("""
            SELECT t.*, c.name AS class_name
            FROM todos t JOIN classes c ON c.id=t.class_id
            WHERE t.class_id=#{classId}
            ORDER BY t.created_at DESC
            """)
    List<Map<String, Object>> listClassTodos(@Param("classId") Object classId);

    @Select("""
            SELECT status, COUNT(*) AS count
            FROM todo_assignees
            WHERE todo_id=#{todoId} GROUP BY status
            """)
    List<Map<String, Object>> countTodoStatuses(@Param("todoId") Object todoId);

    @Select("""
            SELECT t.*, c.name AS class_name, ta.status AS my_status, ta.note, ta.original_filename, ta.submitted_at
            FROM todo_assignees ta JOIN todos t ON t.id=ta.todo_id JOIN classes c ON c.id=t.class_id
            WHERE ta.account_id=#{accountId}
            ORDER BY t.deadline
            """)
    List<Map<String, Object>> listAssignedTodos(@Param("accountId") String accountId);

    @Select("""
            SELECT t.id, t.title, t.status, t.status AS my_status, t.activity_id, ata.account_id, assigned.display_name AS assignee, t.description, t.deadline, t.priority, t.version, a.title AS activity_title, a.status AS activity_status, a.plan_json, a.created_at
            FROM activity_task_assignees ata JOIN tasks t ON t.id=ata.task_id JOIN accounts assigned ON assigned.id=ata.account_id JOIN activities a ON a.id=t.activity_id
            WHERE ata.account_id=#{accountId}
            ORDER BY a.created_at DESC
            """)
    List<Map<String, Object>> listAssignedTasks(@Param("accountId") String accountId);

    @Select("""
            SELECT t.*, c.name AS class_name, c.manager_id
            FROM todos t JOIN classes c ON c.id=t.class_id
            WHERE t.id=#{todoId}
            """)
    Map<String, Object> findTodoRow(@Param("todoId") String todoId);

    default Optional<Map<String, Object>> findTodo(String todoId) {
        return Optional.ofNullable(findTodoRow(todoId));
    }

    @Select("""
            SELECT a.id AS account_id, a.display_name, a.student_no, a.username, ta.status, ta.note, ta.original_filename, ta.submitted_at
            FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id
            WHERE ta.todo_id=#{todoId}
            ORDER BY a.student_no, a.display_name
            """)
    List<Map<String, Object>> listParticipants(@Param("todoId") String todoId);

    @Select("""
            SELECT *
            FROM todo_reminders
            WHERE todo_id=#{todoId}
            ORDER BY remind_at
            """)
    List<Map<String, Object>> listReminders(@Param("todoId") String todoId);

    @Select("""
            SELECT *
            FROM classroom_deliveries
            WHERE todo_id=#{todoId}
            ORDER BY created_at DESC
            """)
    List<Map<String, Object>> listDeliveries(@Param("todoId") String todoId);

    @Select("""
            SELECT *
            FROM todo_assignees
            WHERE todo_id=#{todoId} AND account_id=#{accountId}
            """)
    Map<String, Object> findAssignmentRow(@Param("todoId") String todoId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> findAssignment(String todoId, String accountId) {
        return Optional.ofNullable(findAssignmentRow(todoId, accountId));
    }

    @Select("""
            SELECT 1
            FROM todo_assignees
            WHERE todo_id=#{todoId} AND account_id=#{accountId}
            """)
    Map<String, Object> hasAssignmentRow(@Param("todoId") String todoId, @Param("accountId") String accountId);

    default Optional<Map<String, Object>> hasAssignment(String todoId, String accountId) {
        return Optional.ofNullable(hasAssignmentRow(todoId, accountId));
    }

    @Select("""
            SELECT id
            FROM accounts
            WHERE id=#{accountId} FOR UPDATE
            """)
    Map<String, Object> lockQuotaAccount(@Param("accountId") String accountId);

    @Update("""
            UPDATE todo_assignees
            SET status='done', note=#{note}, file_path=#{assetId}, original_filename=#{filename}, submitted_at=#{submittedAt}, updated_at=#{updatedAt}
            WHERE todo_id=#{todoId} AND account_id=#{accountId}
            """)
    void submitWithFile(@Param("note") String note, @Param("assetId") String assetId, @Param("filename") String filename, @Param("submittedAt") String submittedAt, @Param("updatedAt") String updatedAt, @Param("todoId") String todoId, @Param("accountId") String accountId);

    @Insert("""
            INSERT INTO file_assets (id, todo_id, account_id, storage_key, original_filename, content_type, size_bytes, created_at)
            VALUES (#{id}, #{todoId}, #{accountId}, #{storageKey}, #{filename}, #{contentType}, #{size}, #{now})
            """)
    void insertAsset(@Param("id") String id, @Param("todoId") String todoId, @Param("accountId") String accountId, @Param("storageKey") String storageKey, @Param("filename") String filename, @Param("contentType") String contentType, @Param("size") long size, @Param("now") String now);

    @Update("""
            UPDATE todo_assignees
            SET status='done', note=#{note}, submitted_at=#{submittedAt}, updated_at=#{updatedAt}
            WHERE todo_id=#{todoId} AND account_id=#{accountId}
            """)
    void submitNote(@Param("note") String note, @Param("submittedAt") String submittedAt, @Param("updatedAt") String updatedAt, @Param("todoId") String todoId, @Param("accountId") String accountId);

    @Select("""
            SELECT co.organization_id
            FROM todos t JOIN class_organizations co ON co.class_id=t.class_id
            WHERE t.id=#{todoId}
            """)
    Map<String, Object> findTodoOrganization(@Param("todoId") String todoId);

    @Select("""
            SELECT class_id
            FROM todos
            WHERE id=#{todoId}
            """)
    Map<String, Object> findTodoClassRow(@Param("todoId") String todoId);

    default Optional<Map<String, Object>> findTodoClass(String todoId) {
        return Optional.ofNullable(findTodoClassRow(todoId));
    }

    @Select("""
            SELECT *
            FROM todo_reminders
            WHERE todo_id=#{todoId} AND remind_at=#{remindAt} AND status='scheduled'
            """)
    Map<String, Object> findScheduledReminderRow(@Param("todoId") String todoId, @Param("remindAt") String remindAt);

    default Optional<Map<String, Object>> findScheduledReminder(String todoId, String remindAt) {
        return Optional.ofNullable(findScheduledReminderRow(todoId, remindAt));
    }

    @Insert("""
            INSERT INTO todo_reminders (id, todo_id, remind_at, status, created_at)
            VALUES (#{id}, #{todoId}, #{remindAt}, 'scheduled', #{now})
            """)
    void insertReminder(@Param("id") String id, @Param("todoId") String todoId, @Param("remindAt") String remindAt, @Param("now") String now);

    @Select("""
            SELECT t.*, c.qq_group_openid, co.organization_id
            FROM todos t JOIN classes c ON c.id=t.class_id JOIN class_organizations co ON co.class_id=c.id
            WHERE t.id=#{todoId}
            """)
    Map<String, Object> findTodoChannelRow(@Param("todoId") String todoId);

    default Optional<Map<String, Object>> findTodoChannel(String todoId) {
        return Optional.ofNullable(findTodoChannelRow(todoId));
    }

    @Select("""
            SELECT a.display_name
            FROM todo_assignees ta JOIN accounts a ON a.id=ta.account_id
            WHERE ta.todo_id=#{todoId} AND ta.status!='done'
            ORDER BY a.student_no, a.display_name
            """)
    List<Map<String, Object>> listMissingNames(@Param("todoId") String todoId);

    @Select("""
            SELECT a.id, a.username, a.display_name, a.student_no, a.status, a.created_at
            FROM class_members cm JOIN accounts a ON a.id=cm.account_id
            WHERE cm.class_id=#{classId}
            ORDER BY a.student_no, a.display_name
            """)
    List<Map<String, Object>> listClassMembers(@Param("classId") String classId);

    @Select("""
            SELECT group_openid,content
            FROM outbound_deliveries
            WHERE logical_key=#{key}
            """)
    Map<String, Object> findOutboundRow(@Param("key") String key);

    default Optional<Map<String, Object>> findOutbound(String key) {
        return Optional.ofNullable(findOutboundRow(key));
    }

    @Insert("""
            INSERT INTO organizations (id, name, owner_account_id, created_at)
            VALUES (#{id}, #{name}, #{ownerId}, #{now})
            """)
    void insertOrganization(@Param("id") String id, @Param("name") String name, @Param("ownerId") String ownerId, @Param("now") String now);

    @Insert("""
            INSERT INTO organization_members (organization_id, account_id, role, created_at)
            VALUES (#{id}, #{accountId}, 'owner', #{now})
            """)
    void addOrganizationOwner(@Param("id") String id, @Param("accountId") String accountId, @Param("now") String now);

    @Select("""
            SELECT COUNT(*) AS count
            FROM class_members
            WHERE class_id=#{classId}
            """)
    long countMembers(@Param("classId") Object classId);

    @Select("""
            SELECT COALESCE(SUM(size_bytes),0) AS count
            FROM file_assets
            WHERE account_id=#{accountId}
            """)
    long storageUsed(@Param("accountId") String accountId);
}

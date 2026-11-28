package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface ActivityGateMapper {
    @Select("""
            SELECT *
            FROM activities
            WHERE id=#{id} FOR UPDATE
            """)
    Map<String, Object> lockActivityRow(@Param("id") String id);

    default Optional<Map<String, Object>> lockActivity(String id) {
        return Optional.ofNullable(lockActivityRow(id));
    }

    @Select("""
            SELECT id
            FROM todos
            WHERE id=#{id} FOR UPDATE
            """)
    String lockTodoRow(@Param("id") String id);

    default Optional<String> lockTodo(String id) {
        return Optional.ofNullable(lockTodoRow(id));
    }

    @Select("""
            SELECT *
            FROM activity_changes
            WHERE activity_id=#{activityId} AND request_id=#{requestId}
            """)
    Map<String, Object> findChangeRow(@Param("activityId") String activityId, @Param("requestId") String requestId);

    default Optional<Map<String, Object>> findChange(String activityId, String requestId) {
        return Optional.ofNullable(findChangeRow(activityId, requestId));
    }

    @Insert("""
            INSERT INTO activity_changes(id,activity_id,request_id,kind,actor_id,payload_json,result_json,content,created_at)
            VALUES (#{id},#{activityId},#{requestId},#{kind},#{actorId},#{payload},#{result},#{content},#{now})
            """)
    void insertChange(@Param("id") String id, @Param("activityId") String activityId, @Param("requestId") String requestId, @Param("kind") String kind, @Param("actorId") String actorId, @Param("payload") String payload, @Param("result") String result, @Param("content") String content, @Param("now") String now);

    @Insert("""
            INSERT INTO messages(activity_id,recipient,recipient_account_id,content,created_at)
            VALUES (#{activityId},#{recipient},#{accountId},#{content},#{now})
            """)
    void insertMessage(@Param("activityId") String activityId, @Param("recipient") String recipient, @Param("accountId") String accountId, @Param("content") String content, @Param("now") String now);

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
            SELECT cm.account_id
            FROM class_members cm JOIN activity_classes ac ON ac.class_id=cm.class_id
            WHERE ac.activity_id=#{activityId}
            """)
    List<Map<String, Object>> listParticipants(@Param("activityId") String activityId);
}

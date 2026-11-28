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
public interface QqMapper {
    @Select("""
            SELECT group_openid, group_label, status, last_seen_at
            FROM qq_bindings
            WHERE user_id=#{username} AND status='active'
            ORDER BY created_at
            """)
    List<Map<String, Object>> listUserGroups(@Param("username") String username);

    @Select("""
            SELECT qb.group_openid, qb.group_label, qb.last_seen_at, qb.created_at
            FROM qq_bindings qb JOIN accounts owner ON owner.username=qb.user_id JOIN organization_members om ON om.account_id=owner.id
            WHERE om.organization_id=#{organizationId} AND om.role IN ('owner','manager') AND owner.role='manager' AND owner.status='active' AND qb.status='active'
            ORDER BY qb.created_at, qb.group_openid
            """)
    List<Map<String, Object>> listAuthorizedGroups(@Param("organizationId") String organizationId);

    @Select("""
            SELECT 1
            FROM qq_bindings qb JOIN accounts owner ON owner.username=qb.user_id JOIN organization_members om ON om.account_id=owner.id
            WHERE om.organization_id=#{organizationId} AND om.role IN ('owner','manager') AND owner.role='manager' AND owner.status='active' AND qb.status='active' AND qb.group_openid=#{groupId}
            """)
    Map<String, Object> findAuthorizationRow(@Param("organizationId") String organizationId, @Param("groupId") String groupId);

    default Optional<Map<String, Object>> findAuthorization(String organizationId, String groupId) {
        return Optional.ofNullable(findAuthorizationRow(organizationId, groupId));
    }

    @Select("""
            SELECT 1
            FROM qq_inbound_events
            WHERE message_id=#{messageId}
            """)
    Map<String, Object> findInboundRow(@Param("messageId") String messageId);

    default Optional<Map<String, Object>> findInbound(String messageId) {
        return Optional.ofNullable(findInboundRow(messageId));
    }

    @Insert("""
            INSERT INTO qq_inbound_events (message_id, group_openid, sender_openid, sender_name, content, created_at)
            VALUES (#{messageId}, #{groupId}, #{senderId}, #{senderName}, #{content}, #{now})
            """)
    void insertInbound(@Param("messageId") String messageId, @Param("groupId") String groupId, @Param("senderId") String senderId, @Param("senderName") String senderName, @Param("content") String content, @Param("now") String now);

    @Update("""
            UPDATE qq_bindings
            SET last_seen_at=#{now}
            WHERE group_openid=#{groupId}
            """)
    void touchBinding(@Param("now") String now, @Param("groupId") String groupId);

    @Select("""
            SELECT message_id
            FROM qq_inbound_events
            WHERE group_openid=#{groupId}
            ORDER BY created_at DESC LIMIT 1
            """)
    Map<String, Object> findRecentMessageRow(@Param("groupId") String groupId);

    default Optional<Map<String, Object>> findRecentMessage(String groupId) {
        return Optional.ofNullable(findRecentMessageRow(groupId));
    }

    @Select("""
            SELECT group_openid
            FROM qq_bindings
            WHERE user_id=#{username} AND status='active'
            ORDER BY created_at LIMIT 1
            """)
    Map<String, Object> findFirstGroupRow(@Param("username") String username);

    default Optional<Map<String, Object>> findFirstGroup(String username) {
        return Optional.ofNullable(findFirstGroupRow(username));
    }

    @Insert("""
            INSERT INTO activity_channels (activity_id, user_id, group_openid, created_at)
            VALUES (#{activityId}, #{username}, #{groupId}, #{now})
            """)
    void insertChannel(@Param("activityId") String activityId, @Param("username") String username, @Param("groupId") String groupId, @Param("now") String now);

    @Select("""
            SELECT 1
            FROM accounts a JOIN organization_members om ON om.account_id=a.id
            WHERE a.username=#{username} AND a.role='manager' AND a.status='active' AND om.role IN ('owner','manager')
            """)
    Map<String, Object> findManagerRow(@Param("username") String username);

    default Optional<Map<String, Object>> findManager(String username) {
        return Optional.ofNullable(findManagerRow(username));
    }

    @Select("""
            SELECT code, expires_at
            FROM qq_binding_codes
            WHERE user_id=#{username}
            ORDER BY created_at DESC LIMIT 1
            """)
    Map<String, Object> findRecentCodeRow(@Param("username") String username);

    default Optional<Map<String, Object>> findRecentCode(String username) {
        return Optional.ofNullable(findRecentCodeRow(username));
    }

    @Delete("""
            DELETE
            FROM qq_binding_codes
            WHERE user_id=#{username}
            """)
    void deleteUserCodes(@Param("username") String username);

    @Select("""
            SELECT 1
            FROM qq_binding_codes
            WHERE code=#{code}
            """)
    Map<String, Object> findCodeRow(@Param("code") String code);

    default Optional<Map<String, Object>> findCode(String code) {
        return Optional.ofNullable(findCodeRow(code));
    }

    @Insert("""
            INSERT INTO qq_binding_codes (code, user_id, expires_at, created_at)
            VALUES (#{code}, #{username}, #{expiresAt}, #{now})
            """)
    void insertCode(@Param("code") String code, @Param("username") String username, @Param("expiresAt") String expiresAt, @Param("now") String now);

    @Select("""
            SELECT user_id, expires_at
            FROM qq_binding_codes
            WHERE code=#{code} FOR UPDATE
            """)
    Map<String, Object> lockCodeRow(@Param("code") String code);

    default Optional<Map<String, Object>> lockCode(String code) {
        return Optional.ofNullable(lockCodeRow(code));
    }

    @Insert("""
            INSERT INTO qq_bindings (group_openid, user_id, group_label, status, last_seen_at, created_at)
            VALUES (#{groupId}, #{username}, '已绑定 QQ 群', 'active', #{now}, #{createdAt})
            ON CONFLICT (group_openid) DO UPDATE
            SET user_id=EXCLUDED.user_id, group_label=EXCLUDED.group_label, status='active', last_seen_at=EXCLUDED.last_seen_at
            WHERE qq_bindings.user_id=EXCLUDED.user_id OR EXISTS (SELECT 1
            FROM accounts old_owner JOIN organization_members old_om ON old_om.account_id=old_owner.id JOIN organization_members new_om ON new_om.organization_id=old_om.organization_id JOIN accounts new_owner ON new_owner.id=new_om.account_id
            WHERE old_owner.username=qq_bindings.user_id AND new_owner.username=EXCLUDED.user_id AND old_owner.role='manager' AND old_owner.status='active' AND new_owner.role='manager' AND new_owner.status='active' AND old_om.role IN ('owner','manager') AND new_om.role IN ('owner','manager'))
            """)
    int bindGroup(@Param("groupId") String groupId, @Param("username") String username, @Param("now") String now, @Param("createdAt") String createdAt);

    @Delete("""
            DELETE
            FROM qq_binding_codes
            WHERE code=#{code}
            """)
    void consumeCode(@Param("code") String code);

    @Select("""
            SELECT status, COUNT(*) AS count
            FROM tasks
            WHERE activity_id=#{activityId} GROUP BY status
            """)
    List<Map<String, Object>> listTaskCounts(@Param("activityId") Object activityId);

    @Select("""
            SELECT COUNT(*) AS count
            FROM registrations r JOIN forms f ON f.id=r.form_id
            WHERE f.activity_id=#{activityId}
            """)
    Map<String, Object> countRegistrationsRow(@Param("activityId") Object activityId);

    default Optional<Map<String, Object>> countRegistrations(Object activityId) {
        return Optional.ofNullable(countRegistrationsRow(activityId));
    }

    @Select("""
            SELECT f.id, a.title
            FROM forms f JOIN activities a ON a.id=f.activity_id
            WHERE a.id=#{activityId}
            ORDER BY f.created_at DESC LIMIT 1
            """)
    Map<String, Object> findFormRow(@Param("activityId") Object activityId);

    default Optional<Map<String, Object>> findForm(Object activityId) {
        return Optional.ofNullable(findFormRow(activityId));
    }

    @Select("""
            SELECT title, assignee, status
            FROM tasks
            WHERE activity_id=#{activityId}
            """)
    List<Map<String, Object>> listTasks(@Param("activityId") Object activityId);

    @Select("""
            SELECT a.id, a.title, a.status, ac.organization_id
            FROM activities a JOIN activity_channels c ON c.activity_id=a.id JOIN activity_classes ac ON ac.activity_id=a.id
            WHERE c.group_openid=#{groupId} AND EXISTS (SELECT 1
            FROM qq_bindings qb JOIN accounts binding_owner ON binding_owner.username=qb.user_id JOIN organization_members om ON om.account_id=binding_owner.id
            WHERE qb.group_openid=c.group_openid AND qb.status='active' AND binding_owner.role='manager' AND binding_owner.status='active' AND om.organization_id=ac.organization_id AND om.role IN ('owner','manager'))
            ORDER BY a.created_at DESC LIMIT 1
            """)
    Map<String, Object> findLatestActivityRow(@Param("groupId") String groupId);

    default Optional<Map<String, Object>> findLatestActivity(String groupId) {
        return Optional.ofNullable(findLatestActivityRow(groupId));
    }
}

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
public interface AccountAdministrationMapper {
    @Select("""
            SELECT 1
            FROM privacy_consents
            WHERE account_id=#{accountId} AND policy_version='1.0'
            """)
    Integer findConsentRow(@Param("accountId") String accountId);

    default Optional<Integer> findConsent(String accountId) {
        return Optional.ofNullable(findConsentRow(accountId));
    }

    @Select("""
            SELECT 1
            FROM accounts a JOIN organization_members om ON om.account_id=a.id
            WHERE a.id=#{accountId} AND a.role='participant' AND a.status='active' AND om.organization_id=#{organizationId} AND EXISTS (SELECT 1
            FROM organization_members manager_membership
            WHERE manager_membership.organization_id=om.organization_id AND manager_membership.account_id=#{managerId} AND manager_membership.role IN ('owner','manager')) AND NOT EXISTS (SELECT 1
            FROM organization_members other_membership
            WHERE other_membership.account_id=a.id AND other_membership.organization_id<>om.organization_id)
            FOR UPDATE OF a
            """)
    Integer lockResetTargetRow(@Param("accountId") String accountId, @Param("organizationId") String organizationId, @Param("managerId") String managerId);

    default Optional<Integer> lockResetTarget(String accountId, String organizationId, String managerId) {
        return Optional.ofNullable(lockResetTargetRow(accountId, organizationId, managerId));
    }

    @Insert("""
            INSERT INTO privacy_consents (account_id, policy_version, accepted_at, ip_address)
            VALUES (#{accountId}, '1.0', #{now}, #{ip})
            ON CONFLICT (account_id) DO UPDATE
            SET policy_version='1.0', accepted_at=EXCLUDED.accepted_at, ip_address=EXCLUDED.ip_address
            """)
    void saveConsent(@Param("accountId") String accountId, @Param("now") String now, @Param("ip") String ip);

    @Select("""
            SELECT *
            FROM audit_logs
            WHERE organization_id=#{organizationId}
            ORDER BY created_at DESC LIMIT #{limit}
            """)
    List<Map<String, Object>> listAuditLogs(@Param("organizationId") String organizationId, @Param("limit") int limit);

    @Select("""
            SELECT provider, CASE WHEN provider='phone' THEN subject END AS phone
            FROM account_login_methods
            WHERE account_id=#{accountId}
            """)
    List<Map<String, Object>> listLoginMethods(@Param("accountId") String accountId);

    @Select("""
            SELECT c.id, c.name
            FROM class_members cm JOIN classes c ON c.id=cm.class_id
            WHERE cm.account_id=#{accountId}
            """)
    List<Map<String, Object>> listClasses(@Param("accountId") String accountId);

    @Select("""
            SELECT t.title, ta.status, ta.note, ta.original_filename, ta.submitted_at
            FROM todo_assignees ta JOIN todos t ON t.id=ta.todo_id
            WHERE ta.account_id=#{accountId}
            """)
    List<Map<String, Object>> listSubmissions(@Param("accountId") String accountId);

    @Select("""
            SELECT policy_version, accepted_at
            FROM privacy_consents
            WHERE account_id=#{accountId}
            """)
    List<Map<String, Object>> listConsents(@Param("accountId") String accountId);

    @Select("""
            SELECT 1
            FROM accounts
            WHERE id=#{accountId} FOR UPDATE
            """)
    Integer lockAccountRow(@Param("accountId") String accountId);

    default Optional<Integer> lockAccount(String accountId) {
        return Optional.ofNullable(lockAccountRow(accountId));
    }

    @Select("""
            SELECT storage_key
            FROM file_assets
            WHERE account_id=#{accountId}
            """)
    List<Map<String, Object>> listFileKeys(@Param("accountId") String accountId);

    @Select("""
            SELECT organization_id
            FROM organization_members
            WHERE account_id=#{accountId}
            """)
    List<Map<String, Object>> listOrganizations(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM auth_sessions
            WHERE account_id=#{accountId}
            """)
    void deleteSessions(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM account_login_methods
            WHERE account_id=#{accountId}
            """)
    void deleteLoginMethods(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM phone_login_challenges
            WHERE account_id=#{accountId}
            """)
    void deletePhoneChallenges(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM wechat_login_states
            WHERE binding_account_id=#{accountId}
            """)
    void deleteWechatStates(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM privacy_consents
            WHERE account_id=#{accountId}
            """)
    void deleteConsents(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM class_members
            WHERE account_id=#{accountId}
            """)
    void deleteClassMemberships(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM todo_assignees
            WHERE account_id=#{accountId}
            """)
    void deleteAssignments(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM file_assets
            WHERE account_id=#{accountId}
            """)
    void deleteAssets(@Param("accountId") String accountId);

    @Delete("""
            DELETE
            FROM organization_members
            WHERE account_id=#{accountId}
            """)
    void deleteOrganizationMemberships(@Param("accountId") String accountId);

    @Update("""
            UPDATE accounts
            SET username=#{username}, display_name='已注销用户', student_no=NULL, password_hash=#{passwordHash}, status='deleted'
            WHERE id=#{accountId}
            """)
    void anonymizeAccount(@Param("username") String username, @Param("passwordHash") String passwordHash, @Param("accountId") String accountId);
}

package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface AuthMapper {
    @Select("""
            SELECT 1
            FROM accounts LIMIT 1
            """)
    Map<String, Object> findAnyAccountRow();

    default Optional<Map<String, Object>> findAnyAccount() {
        return Optional.ofNullable(findAnyAccountRow());
    }

    @Select("""
            SELECT a.*, COALESCE(s.force_password_change, 0) AS force_password_change
            FROM auth_sessions x JOIN accounts a ON a.id=x.account_id
            LEFT JOIN account_security s ON s.account_id=a.id
            WHERE x.token_hash=#{tokenHash} AND x.expires_at>#{now} AND a.status='active'
            """)
    Map<String, Object> findSessionRow(@Param("tokenHash") String tokenHash, @Param("now") String now);

    default Optional<Map<String, Object>> findSession(String tokenHash, String now) {
        return Optional.ofNullable(findSessionRow(tokenHash, now));
    }

    @Insert("""
            INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at)
            VALUES (#{accountId}, 0, 0, #{now})
            """)
    void initializeSecurity(@Param("accountId") String accountId, @Param("now") String now);

    @Insert("""
            INSERT INTO organizations (id, name, owner_account_id, created_at)
            VALUES (#{id}, #{name}, #{ownerId}, #{now})
            """)
    void createOrganization(@Param("id") String id, @Param("name") String name, @Param("ownerId") String ownerId, @Param("now") String now);

    @Insert("""
            INSERT INTO organization_members (organization_id, account_id, role, created_at)
            VALUES (#{organizationId}, #{accountId}, 'owner', #{now})
            """)
    void grantOwner(@Param("organizationId") String organizationId, @Param("accountId") String accountId, @Param("now") String now);

    @Insert("""
            INSERT INTO privacy_consents (account_id, policy_version, accepted_at)
            VALUES (#{accountId}, '1.0', #{now})
            """)
    void recordConsent(@Param("accountId") String accountId, @Param("now") String now);

    @Select("""
            SELECT a.*, 0 AS force_password_change
            FROM accounts a
            WHERE id=#{accountId}
            """)
    Map<String, Object> findNewAccountRow(@Param("accountId") String accountId);

    default Optional<Map<String, Object>> findNewAccount(String accountId) {
        return Optional.ofNullable(findNewAccountRow(accountId));
    }

    @Select("""
            SELECT 1
            FROM accounts
            WHERE username=#{username}
            """)
    Map<String, Object> findUsernameRow(@Param("username") String username);

    default Optional<Map<String, Object>> findUsername(String username) {
        return Optional.ofNullable(findUsernameRow(username));
    }

    @Select("""
            SELECT a.*, COALESCE(s.force_password_change,0) AS force_password_change
            FROM accounts a
            LEFT JOIN account_security s ON s.account_id=a.id
            WHERE a.username=#{username} AND a.status='active' FOR UPDATE OF a
            """)
    Map<String, Object> findLoginAccountRow(@Param("username") String username);

    default Optional<Map<String, Object>> findLoginAccount(String username) {
        return Optional.ofNullable(findLoginAccountRow(username));
    }

    @Insert("""
            INSERT INTO accounts (id, username, display_name, student_no, password_hash, role, status, created_at)
            VALUES (#{id}, #{username}, #{displayName}, #{studentNo}, #{passwordHash}, #{role}, 'active', #{now})
            """)
    void createAccount(@Param("id") String id, @Param("username") String username, @Param("displayName") String displayName, @Param("studentNo") String studentNo, @Param("passwordHash") String passwordHash, @Param("role") String role, @Param("now") String now);

    @Insert("""
            INSERT INTO auth_sessions (token_hash, account_id, expires_at, created_at)
            VALUES (#{tokenHash}, #{accountId}, #{expiresAt}, #{now})
            """)
    void createSession(@Param("tokenHash") String tokenHash, @Param("accountId") String accountId, @Param("expiresAt") String expiresAt, @Param("now") String now);

    @Delete("""
            DELETE
            FROM auth_sessions
            WHERE token_hash=#{tokenHash}
            """)
    void deleteSession(@Param("tokenHash") String tokenHash);

    @Select("""
            SELECT password_hash
            FROM accounts
            WHERE id=#{accountId} FOR UPDATE
            """)
    String lockPasswordRow(@Param("accountId") String accountId);

    default Optional<String> lockPassword(String accountId) {
        return Optional.ofNullable(lockPasswordRow(accountId));
    }

    @Update("""
            UPDATE accounts
            SET password_hash=#{passwordHash}
            WHERE id=#{accountId}
            """)
    void updatePassword(@Param("passwordHash") String passwordHash, @Param("accountId") String accountId);

    @Update("""
            UPDATE account_security
            SET force_password_change=#{forceChange}, failed_logins=0, locked_until=NULL, password_changed_at=#{now}
            WHERE account_id=#{accountId}
            """)
    int updateSecurity(@Param("forceChange") int forceChange, @Param("now") String now, @Param("accountId") String accountId);

    @Insert("""
            INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at)
            VALUES (#{accountId}, #{forceChange}, 0, #{now})
            """)
    void createSecurity(@Param("accountId") String accountId, @Param("forceChange") int forceChange, @Param("now") String now);

    @Delete("""
            DELETE
            FROM auth_sessions
            WHERE account_id=#{accountId}
            """)
    void deleteAccountSessions(@Param("accountId") String accountId);

    @Select("""
            SELECT organization_id
            FROM organization_members
            WHERE account_id=#{accountId}
            ORDER BY created_at LIMIT 1
            """)
    Map<String, Object> findOrganizationRow(@Param("accountId") String accountId);

    default Optional<Map<String, Object>> findOrganization(String accountId) {
        return Optional.ofNullable(findOrganizationRow(accountId));
    }
}

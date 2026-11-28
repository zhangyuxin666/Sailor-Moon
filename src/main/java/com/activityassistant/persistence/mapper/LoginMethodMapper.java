package com.activityassistant.persistence.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;
import java.util.Optional;

@Mapper
public interface LoginMethodMapper {
    @Select("""
            SELECT subject FROM account_login_methods WHERE account_id=#{accountId} AND provider='phone'
            """)
    Map<String, Object> findPhoneRow(@Param("accountId") String accountId);

    default Optional<Map<String, Object>> findPhone(String accountId) {
        return Optional.ofNullable(findPhoneRow(accountId));
    }

    @Select("""
            SELECT 1 FROM account_login_methods WHERE account_id=#{accountId} AND provider='wechat'
            """)
    Map<String, Object> findWechatRow(@Param("accountId") String accountId);

    default Optional<Map<String, Object>> findWechat(String accountId) {
        return Optional.ofNullable(findWechatRow(accountId));
    }

    @Insert("""
            INSERT INTO phone_login_challenges (id, account_id, phone, binding_session_hash, code_hash, expires_at)
            VALUES (#{id}, #{accountId}, #{phone}, #{bindingSession}, #{codeHash}, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
            """)
    void createPhoneChallenge(@Param("id") String id, @Param("accountId") String accountId, @Param("phone") String phone, @Param("bindingSession") String bindingSession, @Param("codeHash") String codeHash);

    @Select(value = """
            UPDATE phone_login_challenges
            SET attempts=CASE WHEN code_hash=#{expectedHash} THEN 5 ELSE attempts+1 END
            WHERE id=#{id} AND binding_session_hash IS NOT DISTINCT FROM CAST(#{bindingSession} AS VARCHAR)
            AND expires_at>CURRENT_TIMESTAMP AND attempts<5
            RETURNING account_id, phone, code_hash=#{verifiedHash} AS verified
            """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> consumeCodeRow(@Param("expectedHash") String expectedHash, @Param("id") String id, @Param("bindingSession") String bindingSession, @Param("verifiedHash") String verifiedHash);

    default Optional<Map<String, Object>> consumeCode(String expectedHash, String id, String bindingSession, String verifiedHash) {
        return Optional.ofNullable(consumeCodeRow(expectedHash, id, bindingSession, verifiedHash));
    }

    @Insert("""
            INSERT INTO wechat_login_states (state, browser_hash, binding_account_id, binding_session_hash, expires_at)
            VALUES (#{state}, #{browserHash}, #{accountId}, #{sessionHash}, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
            """)
    void createWechatState(@Param("state") String state, @Param("browserHash") String browserHash, @Param("accountId") String accountId, @Param("sessionHash") String sessionHash);

    @Select(value = """
            DELETE FROM wechat_login_states WHERE state=#{state} AND browser_hash=#{browserHash} AND expires_at>CURRENT_TIMESTAMP
            RETURNING state, binding_account_id, binding_session_hash
            """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> consumeWechatStateRow(@Param("state") String state, @Param("browserHash") String browserHash);

    default Optional<Map<String, Object>> consumeWechatState(String state, String browserHash) {
        return Optional.ofNullable(consumeWechatStateRow(state, browserHash));
    }

    @Delete("""
            DELETE FROM account_login_methods WHERE account_id=#{accountId} AND provider=#{provider}
            """)
    void deleteMethod(@Param("accountId") String accountId, @Param("provider") String provider);

    @Delete("""
            DELETE FROM phone_login_challenges WHERE account_id=#{accountId}
            """)
    void deletePhoneChallenges(@Param("accountId") String accountId);

    @Insert("""
            INSERT INTO account_login_methods (account_id, provider, subject) VALUES (#{accountId}, #{provider}, #{subject})
            ON CONFLICT (account_id, provider) DO UPDATE SET subject=EXCLUDED.subject
            """)
    void bindMethod(@Param("accountId") String accountId, @Param("provider") String provider, @Param("subject") String subject);

    @Select("""
            SELECT a.*, COALESCE(s.force_password_change, 0) AS force_password_change
            FROM accounts a LEFT JOIN account_security s ON s.account_id=a.id
            WHERE a.id=#{accountId} AND a.status='active' FOR UPDATE OF a
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> lockActiveAccountRow(@Param("accountId") String accountId);

    default Optional<Map<String, Object>> lockActiveAccount(String accountId) {
        return Optional.ofNullable(lockActiveAccountRow(accountId));
    }

    @Select("""
            SELECT 1 FROM auth_sessions WHERE token_hash=#{tokenHash} AND account_id=#{accountId} AND expires_at>#{now} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Map<String, Object> lockBindingSessionRow(@Param("tokenHash") String tokenHash, @Param("accountId") String accountId, @Param("now") String now);

    default Optional<Map<String, Object>> lockBindingSession(String tokenHash, String accountId, String now) {
        return Optional.ofNullable(lockBindingSessionRow(tokenHash, accountId, now));
    }

    @Select("""
            SELECT a.id FROM account_login_methods m JOIN accounts a ON a.id=m.account_id
            WHERE m.provider=#{provider} AND m.subject=#{subject} AND a.status='active'
            """)
    Map<String, Object> findBoundAccountRow(@Param("provider") String provider, @Param("subject") String subject);

    default Optional<Map<String, Object>> findBoundAccount(String provider, String subject) {
        return Optional.ofNullable(findBoundAccountRow(provider, subject));
    }

    @Insert("""
            INSERT INTO login_method_limits (bucket, requests, expires_at)
            VALUES (#{bucket}, 1, CURRENT_TIMESTAMP + (#{seconds} * INTERVAL '1 second'))
            ON CONFLICT (bucket) DO UPDATE SET
            requests=CASE WHEN login_method_limits.expires_at<=CURRENT_TIMESTAMP THEN 1
            ELSE login_method_limits.requests+1 END,
            expires_at=CASE WHEN login_method_limits.expires_at<=CURRENT_TIMESTAMP THEN EXCLUDED.expires_at
            ELSE login_method_limits.expires_at END
            WHERE login_method_limits.expires_at<=CURRENT_TIMESTAMP OR login_method_limits.requests<#{requests}
            """)
    int reserveLimit(@Param("bucket") String bucket, @Param("seconds") int seconds, @Param("requests") int requests);

    @Delete("""
            DELETE FROM phone_login_challenges WHERE expires_at<=CURRENT_TIMESTAMP
            """)
    void deleteExpiredPhone();

    @Delete("""
            DELETE FROM wechat_login_states WHERE expires_at<=CURRENT_TIMESTAMP
            """)
    void deleteExpiredWechat();

    @Delete("""
            DELETE FROM login_method_limits WHERE expires_at<=CURRENT_TIMESTAMP
            """)
    void deleteExpiredLimits();
}

package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import com.activityassistant.support.Ids;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    public static final String SESSION_COOKIE = "activity_session";

    private final Db db;
    private final PasswordHasher passwords;
    private final AppProperties properties;
    private final AuditService audits;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    public AuthService(Db db, PasswordHasher passwords, AppProperties properties, AuditService audits) {
        this.db = db;
        this.passwords = passwords;
        this.properties = properties;
        this.audits = audits;
    }

    public boolean initialized() {
        return db.one("SELECT 1 FROM accounts LIMIT 1").isPresent();
    }

    public Optional<AccountPrincipal> findByRawToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return db.one("SELECT a.*, COALESCE(s.force_password_change, 0) AS force_password_change " +
                        "FROM auth_sessions x JOIN accounts a ON a.id=x.account_id " +
                        "LEFT JOIN account_security s ON s.account_id=a.id " +
                        "WHERE x.token_hash=? AND x.expires_at>? AND a.status='active'",
                sha256(token), LocalDateTime.now().toString()).map(this::principal);
    }

    @Transactional
    public LoginResult bootstrap(ApiModels.BootstrapRequest body) {
        if (!body.acceptPrivacy()) throw ApiException.badRequest("必须阅读并同意隐私政策与用户协议");
        if (initialized()) throw ApiException.forbidden("系统已经完成初始化");
        String now = LocalDateTime.now().toString();
        String accountId = createAccount(body.username(), body.displayName(), body.password(), "manager", "");
        db.update("INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) VALUES (?, 0, 0, ?)", accountId, now);
        String organizationId = Ids.shortId();
        db.update("INSERT INTO organizations (id, name, owner_account_id, created_at) VALUES (?, ?, ?, ?)",
                organizationId, body.organizationName(), accountId, now);
        db.update("INSERT INTO organization_members (organization_id, account_id, role, created_at) VALUES (?, ?, 'owner', ?)",
                organizationId, accountId, now);
        db.update("INSERT INTO privacy_consents (account_id, policy_version, accepted_at) VALUES (?, '1.0', ?)", accountId, now);
        audits.write("account.bootstrap", accountId, organizationId, "account", accountId, Map.of("username", body.username()));
        String token = createSession(accountId);
        AccountPrincipal principal = db.one("SELECT a.*, 0 AS force_password_change FROM accounts a WHERE id=?", accountId)
                .map(this::principal).orElseThrow();
        return new LoginResult(principal, token);
    }

    @Transactional
    public LoginResult login(ApiModels.LoginRequest body, String remoteAddress) {
        String key = remoteAddress + ":" + body.username();
        long cutoff = System.currentTimeMillis() - 300_000;
        List<Long> recent = new ArrayList<>(loginAttempts.getOrDefault(key, List.of()));
        recent.removeIf(stamp -> stamp < cutoff);
        if (recent.size() >= 5) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请 5 分钟后再试");
        Map<String, Object> row = db.one("SELECT a.*, COALESCE(s.force_password_change,0) AS force_password_change " +
                "FROM accounts a LEFT JOIN account_security s ON s.account_id=a.id WHERE a.username=? AND a.status='active'", body.username()).orElse(null);
        if (row == null || !passwords.verify(body.password(), String.valueOf(row.get("password_hash")))) {
            recent.add(System.currentTimeMillis());
            loginAttempts.put(key, recent);
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        loginAttempts.remove(key);
        AccountPrincipal principal = principal(row);
        return new LoginResult(principal, createSession(principal.id()));
    }

    public String createAccount(String username, String displayName, String password, String role, String studentNo) {
        String id = Ids.accountId();
        db.update("INSERT INTO accounts (id, username, display_name, student_no, password_hash, role, status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'active', ?)", id, username.trim(), displayName.trim(),
                studentNo == null ? "" : studentNo.trim(), passwords.hash(password), role, LocalDateTime.now().toString());
        return id;
    }

    public String createSession(String accountId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        db.update("INSERT INTO auth_sessions (token_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                sha256(token), accountId, now.plusDays(properties.sessionDays()).toString(), now.toString());
        return token;
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) db.update("DELETE FROM auth_sessions WHERE token_hash=?", sha256(rawToken));
    }

    @Transactional
    public void changePassword(AccountPrincipal account, String currentPassword, String newPassword) {
        String encoded = String.valueOf(db.required("SELECT password_hash FROM accounts WHERE id=?", account.id()).get("password_hash"));
        if (!passwords.verify(currentPassword, encoded)) throw ApiException.badRequest("当前密码错误");
        setPassword(account.id(), newPassword, false);
        audits.write("account.password_change", account.id(), organizationOf(account.id()), "account", account.id(), null);
    }

    public void setPassword(String accountId, String password, boolean forceChange) {
        String now = LocalDateTime.now().toString();
        db.update("UPDATE accounts SET password_hash=? WHERE id=?", passwords.hash(password), accountId);
        int updated = db.update("UPDATE account_security SET force_password_change=?, failed_logins=0, locked_until=NULL, password_changed_at=? WHERE account_id=?",
                forceChange ? 1 : 0, now, accountId);
        if (updated == 0) db.update("INSERT INTO account_security (account_id, force_password_change, failed_logins, password_changed_at) VALUES (?, ?, 0, ?)",
                accountId, forceChange ? 1 : 0, now);
    }

    public AccountPrincipal principal(Map<String, Object> row) {
        return new AccountPrincipal(String.valueOf(row.get("id")), String.valueOf(row.get("username")),
                String.valueOf(row.get("display_name")), row.get("student_no") == null ? null : String.valueOf(row.get("student_no")),
                String.valueOf(row.get("role")), String.valueOf(row.get("status")), String.valueOf(row.get("created_at")),
                ((Number) row.getOrDefault("force_password_change", 0)).intValue());
    }

    public String organizationOf(String accountId) {
        return db.one("SELECT organization_id FROM organization_members WHERE account_id=? ORDER BY created_at LIMIT 1", accountId)
                .map(row -> String.valueOf(row.get("organization_id"))).orElse(null);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record LoginResult(AccountPrincipal account, String token) {}
}

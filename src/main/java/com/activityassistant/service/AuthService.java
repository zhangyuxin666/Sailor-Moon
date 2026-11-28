package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.AuthMapper;
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

    private final AuthMapper accounts;
    private final PasswordHasher passwords;
    private final AppProperties properties;
    private final AuditService audits;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    public AuthService(AuthMapper accounts, PasswordHasher passwords, AppProperties properties, AuditService audits) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.properties = properties;
        this.audits = audits;
    }

    public boolean initialized() {
        return accounts.findAnyAccount().isPresent();
    }

    public Optional<AccountPrincipal> findByRawToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return accounts.findSession(sha256(token), LocalDateTime.now().toString()).map(this::principal);
    }

    @Transactional
    public LoginResult bootstrap(ApiModels.BootstrapRequest body) {
        if (!body.acceptPrivacy()) throw ApiException.badRequest("必须阅读并同意隐私政策与用户协议");
        if (initialized()) throw ApiException.forbidden("系统已经完成初始化");
        String now = LocalDateTime.now().toString();
        String accountId = createAccount(body.username(), body.displayName(), body.password(), "manager", "");
        accounts.initializeSecurity(accountId, now);
        String organizationId = Ids.shortId();
        accounts.createOrganization(organizationId, body.organizationName(), accountId, now);
        accounts.grantOwner(organizationId, accountId, now);
        accounts.recordConsent(accountId, now);
        audits.write("account.bootstrap", accountId, organizationId, "account", accountId, Map.of("username", body.username()));
        String token = createSession(accountId);
        AccountPrincipal principal = accounts.findNewAccount(accountId)
                .map(this::principal).orElseThrow();
        return new LoginResult(principal, token);
    }

    @Transactional
    public LoginResult register(ApiModels.RegisterRequest body) {
        if (!body.acceptPrivacy()) throw ApiException.badRequest("必须阅读并同意隐私政策与用户协议");
        if (!List.of("manager", "participant").contains(body.role())) throw ApiException.badRequest("请选择管理者或参与者");
        if (!initialized()) throw ApiException.badRequest("请先完成首位管理者初始化");
        if (accounts.findUsername(body.username()).isPresent())
            throw ApiException.badRequest("用户名已被使用，请换一个");
        String now = LocalDateTime.now().toString();
        String accountId;
        try {
            accountId = createAccount(body.username(), body.displayName(), body.password(), body.role(), "");
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw ApiException.badRequest("用户名已被使用，请换一个");
        }
        accounts.initializeSecurity(accountId, now);
        String organizationId = null;
        if ("manager".equals(body.role())) {
            organizationId = Ids.shortId();
            String name = body.organizationName() == null || body.organizationName().isBlank() ? body.displayName().trim() + "的组织" : body.organizationName().trim();
            accounts.createOrganization(organizationId, name, accountId, now);
            accounts.grantOwner(organizationId, accountId, now);
        }
        accounts.recordConsent(accountId, now);
        audits.write("account.register", accountId, organizationId, "account", accountId, Map.of("role", body.role()));
        AccountPrincipal account = accounts.findNewAccount(accountId).map(this::principal).orElseThrow();
        return new LoginResult(account, createSession(accountId));
    }

    @Transactional
    public LoginResult login(ApiModels.LoginRequest body, String remoteAddress) {
        String key = remoteAddress + ":" + body.username();
        long cutoff = System.currentTimeMillis() - 300_000;
        List<Long> recent = new ArrayList<>(loginAttempts.getOrDefault(key, List.of()));
        recent.removeIf(stamp -> stamp < cutoff);
        if (recent.size() >= 5) throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请 5 分钟后再试");
        Map<String, Object> row = accounts.findLoginAccount(body.username()).orElse(null);
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
        accounts.createAccount(id, username.trim(), displayName.trim(),
                studentNo == null ? "" : studentNo.trim(), passwords.hash(password), role, LocalDateTime.now().toString());
        return id;
    }

    public String createSession(String accountId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        accounts.createSession(sha256(token), accountId, now.plusDays(properties.sessionDays()).toString(), now.toString());
        return token;
    }

    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) accounts.deleteSession(sha256(rawToken));
    }

    @Transactional
    public void changePassword(AccountPrincipal account, String currentPassword, String newPassword) {
        String encoded = accounts.lockPassword(account.id()).orElseThrow();
        if (!passwords.verify(currentPassword, encoded)) throw ApiException.badRequest("当前密码错误");
        if (passwords.verify(newPassword, encoded)) throw ApiException.badRequest("新密码不能与当前密码相同");
        setPassword(account.id(), newPassword, false);
        audits.write("account.password_change", account.id(), organizationOf(account.id()), "account", account.id(), null);
    }

    @Transactional
    public void setPassword(String accountId, String password, boolean forceChange) {
        String now = LocalDateTime.now().toString();
        accounts.updatePassword(passwords.hash(password), accountId);
        int updated = accounts.updateSecurity(forceChange ? 1 : 0, now, accountId);
        if (updated == 0) accounts.createSecurity(accountId, forceChange ? 1 : 0, now);
        // A reset invalidates every device, including sessions created before a concurrent reset committed.
        accounts.deleteAccountSessions(accountId);
    }

    public AccountPrincipal principal(Map<String, Object> row) {
        return new AccountPrincipal(String.valueOf(row.get("id")), String.valueOf(row.get("username")),
                String.valueOf(row.get("display_name")), row.get("student_no") == null ? null : String.valueOf(row.get("student_no")),
                String.valueOf(row.get("role")), String.valueOf(row.get("status")), String.valueOf(row.get("created_at")),
                ((Number) row.getOrDefault("force_password_change", 0)).intValue());
    }

    public String organizationOf(String accountId) {
        return accounts.findOrganization(accountId)
                .map(row -> String.valueOf(row.get("organization_id"))).orElse(null);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record LoginResult(AccountPrincipal account, String token) {}
}

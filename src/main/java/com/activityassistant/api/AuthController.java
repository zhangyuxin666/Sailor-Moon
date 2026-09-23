package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AuditService;
import com.activityassistant.service.AuthService;
import com.activityassistant.service.StorageService;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class AuthController {
    private final AuthService auth;
    private final Db db;
    private final AppProperties properties;
    private final AuditService audits;
    private final StorageService storage;
    private final ObjectMapper objectMapper;

    public AuthController(AuthService auth, Db db, AppProperties properties, AuditService audits,
                          StorageService storage, ObjectMapper objectMapper) {
        this.auth = auth;
        this.db = db;
        this.properties = properties;
        this.audits = audits;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/auth/status")
    Map<String, Object> status(Authentication authentication) {
        AccountPrincipal account = optional(authentication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("initialized", auth.initialized());
        if (account == null) {
            result.put("account", null);
        } else {
            Map<String, Object> publicAccount = account.toPublicMap();
            publicAccount.put("privacy_accepted", db.one("SELECT 1 FROM privacy_consents WHERE account_id=? AND policy_version='1.0'", account.id()).isPresent());
            result.put("account", publicAccount);
        }
        return result;
    }

    @PostMapping("/auth/bootstrap")
    ResponseEntity<Map<String, Object>> bootstrap(@Valid @RequestBody ApiModels.BootstrapRequest body) {
        AuthService.LoginResult result = auth.bootstrap(body);
        return withCookie(result);
    }

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, Object>> login(@Valid @RequestBody ApiModels.LoginRequest body,
                                               HttpServletRequest request) {
        return withCookie(auth.login(body, request.getRemoteAddr()));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        auth.logout(rawToken(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).body(Map.of("status", "ok"));
    }

    @PostMapping("/auth/change-password")
    Map<String, String> changePassword(Authentication authentication,
                                       @Valid @RequestBody ApiModels.PasswordChangeRequest body) {
        auth.changePassword(required(authentication), body.currentPassword(), body.newPassword());
        return Map.of("status", "ok");
    }

    @PostMapping("/accounts/{accountId}/reset-password")
    @Transactional
    Map<String, Object> resetPassword(Authentication authentication, @PathVariable String accountId,
                                      @Valid @RequestBody ApiModels.PasswordResetRequest body) {
        AccountPrincipal manager = required(authentication);
        if (!manager.isManager()) throw ApiException.forbidden("只有管理者可以重置成员密码");
        String organizationId = auth.organizationOf(manager.id());
        boolean allowed = db.one("SELECT 1 FROM accounts a JOIN organization_members om ON om.account_id=a.id " +
                "WHERE a.id=? AND a.role='participant' AND om.organization_id=?", accountId, organizationId).isPresent();
        if (!allowed) throw ApiException.forbidden("无权重置该账号密码");
        auth.setPassword(accountId, body.newPassword(), true);
        audits.write("account.password_reset", manager.id(), organizationId, "account", accountId, null);
        return Map.of("status", "ok", "force_password_change", true);
    }

    @PostMapping("/privacy/consent")
    @Transactional
    Map<String, String> privacy(Authentication authentication, HttpServletRequest request,
                                @RequestBody ApiModels.PrivacyConsentRequest body) {
        if (!body.accept()) throw ApiException.badRequest("必须同意隐私政策后才能继续使用");
        AccountPrincipal account = required(authentication);
        db.update("INSERT INTO privacy_consents (account_id, policy_version, accepted_at, ip_address) VALUES (?, '1.0', ?, ?) " +
                        "ON CONFLICT (account_id) DO UPDATE SET policy_version='1.0', accepted_at=EXCLUDED.accepted_at, ip_address=EXCLUDED.ip_address",
                account.id(), LocalDateTime.now().toString(), request.getRemoteAddr());
        return Map.of("status", "ok", "policy_version", "1.0");
    }

    @GetMapping("/audit-logs")
    Map<String, Object> auditLogs(Authentication authentication,
                                  @RequestParam(defaultValue = "100") int limit) {
        AccountPrincipal account = required(authentication);
        if (!account.isManager()) throw ApiException.forbidden("只有管理者可以查看审计日志");
        String organizationId = auth.organizationOf(account.id());
        List<Map<String, Object>> logs = db.list("SELECT * FROM audit_logs WHERE organization_id=? ORDER BY created_at DESC LIMIT ?",
                organizationId, Math.min(Math.max(limit, 1), 500));
        return Map.of("logs", logs);
    }

    @GetMapping("/account/export")
    ResponseEntity<byte[]> export(Authentication authentication) throws JsonProcessingException {
        AccountPrincipal account = required(authentication);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exported_at", LocalDateTime.now().toString());
        payload.put("account", account.toPublicMap());
        payload.put("classes", db.list("SELECT c.id, c.name FROM class_members cm JOIN classes c ON c.id=cm.class_id WHERE cm.account_id=?", account.id()));
        payload.put("submissions", db.list("SELECT t.title, ta.status, ta.note, ta.original_filename, ta.submitted_at FROM todo_assignees ta JOIN todos t ON t.id=ta.todo_id WHERE ta.account_id=?", account.id()));
        payload.put("privacy_consents", db.list("SELECT policy_version, accepted_at FROM privacy_consents WHERE account_id=?", account.id()));
        byte[] data = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data.json\"").body(data);
    }

    @DeleteMapping("/account")
    @Transactional
    ResponseEntity<Map<String, String>> deleteAccount(Authentication authentication) {
        AccountPrincipal account = required(authentication);
        if (account.isManager()) throw ApiException.badRequest("管理者需要先转移或删除名下组织，暂时不能直接注销");
        db.list("SELECT storage_key FROM file_assets WHERE account_id=?", account.id())
                .forEach(asset -> storage.delete(String.valueOf(asset.get("storage_key"))));
        List<Map<String, Object>> organizations = db.list("SELECT organization_id FROM organization_members WHERE account_id=?", account.id());
        organizations.forEach(org -> audits.write("account.delete", account.id(), String.valueOf(org.get("organization_id")), "account", account.id(), null));
        db.update("DELETE FROM auth_sessions WHERE account_id=?", account.id());
        db.update("DELETE FROM privacy_consents WHERE account_id=?", account.id());
        db.update("DELETE FROM class_members WHERE account_id=?", account.id());
        db.update("DELETE FROM todo_assignees WHERE account_id=?", account.id());
        db.update("DELETE FROM file_assets WHERE account_id=?", account.id());
        db.update("DELETE FROM organization_members WHERE account_id=?", account.id());
        db.update("UPDATE accounts SET username=?, display_name='已注销用户', student_no=NULL, password_hash=?, status='deleted' WHERE id=?",
                "deleted-" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(), account.id());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(Map.of("status", "deleted", "deleted_at", LocalDateTime.now().toString()));
    }

    static AccountPrincipal required(Authentication authentication) {
        AccountPrincipal account = optional(authentication);
        if (account == null) throw ApiException.forbidden("请先登录");
        return account;
    }

    static AccountPrincipal optional(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal account ? account : null;
    }

    private ResponseEntity<Map<String, Object>> withCookie(AuthService.LoginResult result) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie(result.token()).toString())
                .body(result.account().toPublicMap());
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE, token).httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/").maxAge(Duration.ofDays(properties.sessionDays())).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(AuthService.SESSION_COOKIE, "").httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/").maxAge(Duration.ZERO).build();
    }

    private String rawToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(cookie -> AuthService.SESSION_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
}

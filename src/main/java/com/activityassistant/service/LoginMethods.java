package com.activityassistant.service;

import com.activityassistant.config.LoginMethodProperties;
import com.activityassistant.persistence.mapper.LoginMethodMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LoginMethods {
    private final LoginMethodMapper loginData;
    private final AuthService auth;
    private final PasswordHasher passwords;
    private final LoginProviders providers;
    private final LoginMethodProperties properties;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public LoginMethods(LoginMethodMapper loginData, AuthService auth, PasswordHasher passwords, LoginProviders providers,
                        LoginMethodProperties properties, PlatformTransactionManager transactions) {
        this.loginData = loginData;
        this.auth = auth;
        this.passwords = passwords;
        this.providers = providers;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
    }

    public Map<String, Boolean> availability() {
        return Map.of("phone", providers.phoneEnabled(), "wechat", providers.wechatEnabled());
    }

    public Map<String, Object> bindings(String accountId) {
        var result = new LinkedHashMap<String, Object>(availability());
        String phone = loginData.findPhone(accountId)
                .map(row -> (String) row.get("subject")).orElse(null);
        result.put("bound_phone", phone == null ? null : phone.substring(0, 3) + "****" + phone.substring(7));
        result.put("wechat_bound", loginData.findWechat(accountId).isPresent());
        return result;
    }

    public Binding authorizeBinding(AccountPrincipal account, String sessionToken, String password, String address) {
        // Limit password checks here separately from SMS sends and the existing password login.
        reserve(new Limit("bind:account:" + account.id(), 10, 300), ipLimit("bind", address, 60, 300));
        String hash = AuthService.sha256(sessionToken);
        transaction.executeWithoutResult(status -> {
            var row = activeAccount(account.id());
            checkBindingSession(account.id(), hash, row);
            if (!passwords.verify(password, (String) row.get("password_hash"))) {
                throw ApiException.badRequest("当前密码错误");
            }
        });
        return new Binding(account.id(), hash);
    }

    public String sendCode(String phone, Binding binding, String address) {
        requireEnabled(providers.phoneEnabled(), "手机号登录暂未启用");
        reserve(ipLimit("sms", address, 60, 3600),
                new Limit("sms:phone:" + AuthService.sha256(phone), 1, 60),
                new Limit("sms:hour:" + AuthService.sha256(phone), 5, 3600));
        String id = id();
        String accountId = binding == null ? accountFor("phone", phone) : binding.accountId();
        // Return the same response shape for unknown numbers without sending a message.
        if (accountId == null) return id;
        reserve(new Limit("sms:total", 300, 3600));
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        providers.sendPhoneCode(phone, code);
        loginData.createPhoneChallenge(id, accountId, phone, binding == null ? null : binding.sessionHash(), codeHash(id, code));
        return id;
    }

    public AuthService.LoginResult loginByPhone(String id, String code) {
        Map<String, Object> challenge = verifyCode(id, code, null);
        return signIn("phone", (String) challenge.get("phone"), (String) challenge.get("account_id"));
    }

    public void bindPhone(String id, String code, AccountPrincipal account, String sessionToken) {
        Binding binding = new Binding(account.id(), AuthService.sha256(sessionToken));
        Map<String, Object> challenge = verifyCode(id, code, binding.sessionHash());
        if (!account.id().equals(challenge.get("account_id"))) throw invalidCode();
        bind("phone", (String) challenge.get("phone"), binding);
    }

    private Map<String, Object> verifyCode(String id, String code, String bindingSession) {
        requireEnabled(providers.phoneEnabled(), "手机号登录暂未启用");
        String hash = codeHash(id, code);
        return loginData.consumeCode(hash, id, bindingSession, hash)
                .filter(row -> Boolean.TRUE.equals(row.get("verified"))).orElseThrow(LoginMethods::invalidCode);
    }

    public WechatStart startWechat(Binding binding, String address) {
        requireEnabled(providers.wechatEnabled(), "微信扫码登录暂未启用");
        reserve(ipLimit("wechat", address, 60, 60));
        String state = id();
        String browser = id() + id();
        loginData.createWechatState(state, AuthService.sha256(browser), binding == null ? null : binding.accountId(),
                binding == null ? null : binding.sessionHash());
        return new WechatStart(providers.wechatUrl(state), browser);
    }

    public WechatResult finishWechat(String state, String browser, String code) {
        if (!providers.wechatEnabled() || state == null || !state.matches("[a-f0-9]{32}")
                || browser == null || !browser.matches("[a-f0-9]{64}")) {
            return new WechatResult(false, "expired", null);
        }
        var flow = loginData.consumeWechatState(state, AuthService.sha256(browser)).orElse(null);
        if (flow == null) return new WechatResult(false, "expired", null);
        String bindingAccountId = (String) flow.get("binding_account_id");
        boolean binding = bindingAccountId != null;
        if (code == null || code.isBlank() || code.length() > 256) return new WechatResult(binding, "cancelled", null);
        try {
            String subject = providers.wechatSubject(code);
            if (binding) {
                bind("wechat", subject, new Binding(bindingAccountId, (String) flow.get("binding_session_hash")));
                return new WechatResult(true, "bound", null);
            }
            String accountId = accountFor("wechat", subject);
            if (accountId == null) return new WechatResult(false, "unbound", null);
            return new WechatResult(false, "ok", signIn("wechat", subject, accountId));
        } catch (ApiException exception) {
            String result = exception.status() == HttpStatus.CONFLICT ? "conflict" : "failed";
            return new WechatResult(binding, result, null);
        }
    }

    public void remove(String provider, Binding binding) {
        if (!"phone".equals(provider) && !"wechat".equals(provider)) throw ApiException.notFound("登录方式不存在");
        transaction.executeWithoutResult(status -> {
            checkBindingSession(binding.accountId(), binding.sessionHash(), activeAccount(binding.accountId()));
            loginData.deleteMethod(binding.accountId(), provider);
            if ("phone".equals(provider)) {
                loginData.deletePhoneChallenges(binding.accountId());
            }
        });
    }

    private void bind(String provider, String subject, Binding binding) {
        try {
            transaction.executeWithoutResult(status -> {
                checkBindingSession(binding.accountId(), binding.sessionHash(), activeAccount(binding.accountId()));
                loginData.bindMethod(binding.accountId(), provider, subject);
            });
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "该登录方式已绑定其他账号");
        }
    }

    private AuthService.LoginResult signIn(String provider, String subject, String accountId) {
        return transaction.execute(status -> {
            var row = activeAccount(accountId);
            if (!accountId.equals(accountFor(provider, subject))) {
                throw ApiException.badRequest("登录方式未绑定或已变更，请重新登录");
            }
            return new AuthService.LoginResult(auth.principal(row), auth.createSession(accountId));
        });
    }

    private Map<String, Object> activeAccount(String accountId) {
        return loginData.lockActiveAccount(accountId).orElseThrow(() -> ApiException.badRequest("账号不可用"));
    }

    private void checkBindingSession(String accountId, String hash, Map<String, Object> account) {
        if (((Number) account.get("force_password_change")).intValue() > 0
                || loginData.lockBindingSession(hash, accountId, LocalDateTime.now().toString()).isEmpty()) {
            throw ApiException.forbidden("绑定授权已失效，请重新登录后操作");
        }
    }

    private String accountFor(String provider, String subject) {
        return loginData.findBoundAccount(provider, subject).map(row -> (String) row.get("id")).orElse(null);
    }

    private String codeHash(String id, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.smsAccessKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法校验短信验证码", exception);
        }
    }

    private void reserve(Limit... limits) {
        transaction.executeWithoutResult(status -> {
            for (Limit limit : limits) {
                int updated = loginData.reserveLimit(limit.key(), limit.seconds(), limit.requests());
                if (updated == 0) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试");
            }
        });
    }

    @Scheduled(fixedDelay = 300_000)
    void removeExpiredChallenges() {
        loginData.deleteExpiredPhone();
        loginData.deleteExpiredWechat();
        loginData.deleteExpiredLimits();
    }

    private static Limit ipLimit(String kind, String address, int requests, int seconds) {
        return new Limit(kind + ":ip:" + AuthService.sha256(address), requests, seconds);
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireEnabled(boolean enabled, String message) {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private static ApiException invalidCode() {
        return ApiException.badRequest("验证码无效、已过期或尝试次数过多，请重新获取");
    }

    private record Limit(String key, int requests, int seconds) {}
    public record Binding(String accountId, String sessionHash) {}
    public record WechatStart(String url, String browser) {}
    public record WechatResult(boolean binding, String status, AuthService.LoginResult login) {}
}

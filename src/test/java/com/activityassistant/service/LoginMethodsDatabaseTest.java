package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.config.LoginMethodProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "login.test.jdbc", matches = ".+")
class LoginMethodsDatabaseTest {
    private static final String PASSWORD = "test-password-123";
    private final LoginProviders providers = mock(LoginProviders.class);
    private Db db;
    private AuthService auth;
    private LoginMethods methods;
    private String accountId;
    private String token;
    private String address;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(System.getProperty("login.test.jdbc"),
                System.getProperty("login.test.user", "activity"), System.getProperty("login.test.password", "activity"));
        db = new Db(new JdbcTemplate(dataSource));
        var app = mock(AppProperties.class);
        when(app.sessionDays()).thenReturn(7);
        var passwords = new PasswordHasher();
        auth = new AuthService(db, passwords, app, mock(AuditService.class));
        methods = new LoginMethods(db, auth, passwords, providers,
                new LoginMethodProperties("", "", "test-key", "test-secret", "test-sign", "test-template", ""),
                new DataSourceTransactionManager(dataSource));
        address = UUID.randomUUID().toString();
        accountId = auth.createAccount("login-test-" + address.substring(0, 8), "Test Member", PASSWORD, "participant", "");
        token = auth.createSession(accountId);
    }

    @AfterEach
    void tearDown() {
        if (accountId == null) return;
        db.update("DELETE FROM auth_sessions WHERE account_id=?", accountId);
        db.update("DELETE FROM accounts WHERE id=?", accountId);
    }

    @Test
    void smsBindingChallengeCannotLogInAndLoginChallengeIsSingleUse() {
        when(providers.phoneEnabled()).thenReturn(true);
        String phone = "13" + String.format(java.util.Locale.ROOT, "%09d", new java.security.SecureRandom().nextInt(1_000_000_000));
        var account = auth.findByRawToken(token).orElseThrow();
        var binding = methods.authorizeBinding(account, token, PASSWORD, address);
        String bindingId = methods.sendCode(phone, binding, address);
        var sent = ArgumentCaptor.forClass(String.class);
        verify(providers).sendPhoneCode(eq(phone), sent.capture());
        String bindingCode = sent.getValue();
        assertThatThrownBy(() -> methods.loginByPhone(bindingId, bindingCode)).isInstanceOf(ApiException.class);
        methods.bindPhone(bindingId, bindingCode, account, token);
        assertThat(methods.bindings(accountId)).containsEntry("bound_phone", phone.substring(0, 3) + "****" + phone.substring(7));

        assertThatThrownBy(() -> methods.sendCode(phone, null, address)).isInstanceOf(ApiException.class).hasMessageContaining("频繁");
        db.update("DELETE FROM login_method_limits WHERE bucket=?", "sms:phone:" + AuthService.sha256(phone));
        String loginId = methods.sendCode(phone, null, address);
        verify(providers, times(2)).sendPhoneCode(eq(phone), sent.capture());
        String loginCode = sent.getValue();
        String wrong = "000000".equals(loginCode) ? "111111" : "000000";
        assertThatThrownBy(() -> methods.loginByPhone(loginId, wrong)).isInstanceOf(ApiException.class);
        var login = methods.loginByPhone(loginId, loginCode);
        assertThat(auth.findByRawToken(login.token())).contains(account);
        assertThatThrownBy(() -> methods.loginByPhone(loginId, loginCode)).isInstanceOf(ApiException.class);
    }

    @Test
    void wechatRequiresTheStartingBrowserAndCannotBindAfterLogout() {
        when(providers.wechatEnabled()).thenReturn(true);
        when(providers.wechatUrl(anyString())).thenAnswer(call -> call.getArgument(0));
        when(providers.wechatSubject("wechat-code")).thenReturn("app:" + UUID.randomUUID());
        var account = auth.findByRawToken(token).orElseThrow();
        var binding = methods.authorizeBinding(account, token, PASSWORD, address);
        var start = methods.startWechat(binding, address);
        assertThat(methods.finishWechat(start.url(), "0".repeat(64), "wechat-code").status()).isEqualTo("expired");
        verify(providers, never()).wechatSubject(anyString());
        assertThat(methods.finishWechat(start.url(), start.browser(), "wechat-code").status()).isEqualTo("bound");
        assertThat(methods.finishWechat(start.url(), start.browser(), "wechat-code").status()).isEqualTo("expired");

        var login = methods.startWechat(null, address);
        var result = methods.finishWechat(login.url(), login.browser(), "wechat-code");
        assertThat(auth.findByRawToken(result.login().token())).contains(account);

        var abandoned = methods.startWechat(binding, address);
        auth.logout(token);
        assertThat(methods.finishWechat(abandoned.url(), abandoned.browser(), "wechat-code").status()).isEqualTo("failed");
    }
}

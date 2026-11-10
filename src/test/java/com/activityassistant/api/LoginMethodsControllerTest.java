package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AuthService;
import com.activityassistant.service.LoginMethods;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginMethodsControllerTest {
    private final LoginMethods methods = mock(LoginMethods.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginMethodsController(methods, properties))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
            .build();

    @Test
    void phoneLoginConsumesSnakeCaseChallengeAndUsesExistingSessionCookie() throws Exception {
        String challenge = "a".repeat(32);
        when(properties.cookieSecure()).thenReturn(true);
        when(properties.sessionDays()).thenReturn(7);
        when(methods.loginByPhone(challenge, "123456")).thenReturn(login());

        mvc.perform(post("/auth/phone/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge_id\":\"" + challenge + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(AuthService.SESSION_COOKIE, true))
                .andExpect(cookie().secure(AuthService.SESSION_COOKIE, true))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.force_password_change").value(1))
                .andExpect(content().string(not(containsString("session-token"))));
    }

    @Test
    void wechatUsesSeparateBrowserCookieAndExchangesItForTheSession() throws Exception {
        when(properties.sessionDays()).thenReturn(7);
        when(methods.startWechat(null, "127.0.0.1")).thenReturn(
                new LoginMethods.WechatStart("https://open.weixin.qq.com/connect/qrconnect", "browser-proof"));
        when(methods.finishWechat("state", "browser-proof", "code")).thenReturn(
                new LoginMethods.WechatResult(false, "ok", login()));

        mvc.perform(post("/auth/wechat/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(cookie().path("activity_wechat_flow", "/auth/wechat/callback"))
                .andExpect(cookie().httpOnly("activity_wechat_flow", true))
                .andExpect(cookie().secure("activity_wechat_flow", true))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(content().string(not(containsString("browser-proof"))));

        mvc.perform(get("/auth/wechat/callback").queryParam("state", "state").queryParam("code", "code")
                        .cookie(new Cookie("activity_wechat_flow", "browser-proof")))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/login?screen=account&wechat=ok"))
                .andExpect(cookie().maxAge("activity_wechat_flow", 0))
                .andExpect(cookie().value(AuthService.SESSION_COOKIE, "session-token"));
    }

    private static AuthService.LoginResult login() {
        return new AuthService.LoginResult(new AccountPrincipal(
                "account", "student", "Student", "", "participant", "active", "2026-10-25T10:00:00", 1), "session-token");
    }
}

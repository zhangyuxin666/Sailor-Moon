package com.activityassistant.security;

import com.activityassistant.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionAuthenticationFilterTest {
    private final AuthService auth = mock(AuthService.class);
    private final SessionAuthenticationFilter filter = new SessionAuthenticationFilter(auth);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("allowedRequests")
    void forcedAccountCanOnlyReachRequiredAuthAndPublicResources(String method, String path) throws Exception {
        authenticate(1);
        AtomicBoolean proceeded = new AtomicBoolean();
        MockHttpServletResponse response = request(method, path, "", proceeded);

        assertThat(proceeded).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    static Stream<Arguments> allowedRequests() {
        return Stream.of(
                Arguments.of("GET", "/auth/status"),
                Arguments.of("POST", "/auth/change-password"),
                Arguments.of("POST", "/auth/logout"),
                Arguments.of("POST", "/auth/login"),
                Arguments.of("GET", "/settings"),
                Arguments.of("HEAD", "/settings"),
                Arguments.of("GET", "/login"),
                Arguments.of("GET", "/"),
                Arguments.of("GET", "/assets/app-123.js"),
                Arguments.of("GET", "/static/styles.css"),
                Arguments.of("GET", "/favicon.svg"),
                Arguments.of("GET", "/forms/form-1"),
                Arguments.of("GET", "/public/forms/form-1"),
                Arguments.of("POST", "/forms/form-1/registrations"),
                Arguments.of("POST", "/internal/qq/events"));
    }

    @ParameterizedTest
    @MethodSource("blockedRequests")
    void forcedAccountCannotBypassUsingBusinessMethodsOrPathVariants(String method, String path) throws Exception {
        authenticate(1);
        AtomicBoolean proceeded = new AtomicBoolean();
        MockHttpServletResponse response = request(method, path, "", proceeded);

        assertThat(proceeded).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":\"password_change_required\"");
    }

    static Stream<Arguments> blockedRequests() {
        return Stream.of(
                Arguments.of("GET", "/portal/dashboard"),
                Arguments.of("POST", "/activities"),
                Arguments.of("GET", "/activities/a1"),
                Arguments.of("POST", "/agent/drafts"),
                Arguments.of("PATCH", "/tasks/t1"),
                Arguments.of("DELETE", "/account"),
                Arguments.of("POST", "/privacy/consent"),
                Arguments.of("POST", "/accounts/u2/reset-password"),
                Arguments.of("GET", "/audit-logs"),
                Arguments.of("POST", "/settings"),
                Arguments.of("GET", "/auth/change-password"),
                Arguments.of("POST", "/auth/status"),
                Arguments.of("POST", "/assets/app.js"),
                Arguments.of("GET", "/assets/../portal/dashboard"),
                Arguments.of("GET", "/assets/%2e%2e/portal/dashboard"),
                Arguments.of("GET", "/assets;anything/../portal/dashboard"),
                Arguments.of("GET", "//auth/status"),
                Arguments.of("POST", "/auth/change-password/"),
                Arguments.of("POST", "/auth/change-password;ignored"),
                Arguments.of("GET", "/internal/qq/events"));
    }

    @Test
    void exactAllowlistAlsoWorksUnderAServletContext() throws Exception {
        authenticate(1);
        AtomicBoolean proceeded = new AtomicBoolean();

        assertThat(request("POST", "/app/auth/change-password", "/app", proceeded).getStatus()).isEqualTo(200);
        assertThat(proceeded).isTrue();

        proceeded.set(false);
        assertThat(request("GET", "/app/portal/dashboard", "/app", proceeded).getStatus()).isEqualTo(403);
        assertThat(proceeded).isFalse();
    }

    @Test
    void ordinaryAccountCanReachProtectedBusinessRequests() throws Exception {
        authenticate(0);
        AtomicBoolean proceeded = new AtomicBoolean();

        request("POST", "/activities", "", proceeded);

        assertThat(proceeded).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOf(AccountPrincipal.class);
    }

    @Test
    void anonymousRequestContinuesToNormalSecurityAuthorization() throws Exception {
        when(auth.findByRawToken("session-token")).thenReturn(Optional.empty());
        AtomicBoolean proceeded = new AtomicBoolean();

        request("POST", "/activities", "", proceeded);

        assertThat(proceeded).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void authenticate(int forceChange) {
        when(auth.findByRawToken("session-token")).thenReturn(Optional.of(new AccountPrincipal(
                "u1", "student", "学生", "1", "participant", "active", "2026-10-25T10:00:00", forceChange)));
    }

    private MockHttpServletResponse request(String method, String path, String context, AtomicBoolean proceeded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath(context);
        request.setCookies(new Cookie(AuthService.SESSION_COOKIE, "session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> proceeded.set(true));
        return response;
    }
}

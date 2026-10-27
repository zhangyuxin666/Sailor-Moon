package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AuditService;
import com.activityassistant.service.AuthService;
import com.activityassistant.service.StorageService;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerSecurityTest {
    private final AuthService auth = mock(AuthService.class);
    private final Db db = mock(Db.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final AuthController controller = new AuthController(
            auth, db, properties, mock(AuditService.class), mock(StorageService.class), new ObjectMapper());

    @Test
    void passwordChangeExpiresCookieAndRequiresReauthentication() {
        var principal = principal("student-1", "participant");
        var authentication = new UsernamePasswordAuthenticationToken(principal, "old-token");
        when(properties.cookieSecure()).thenReturn(true);

        var response = controller.changePassword(authentication, new ApiModels.PasswordChangeRequest("old-password", "new-password"));

        assertThat(response.getBody()).containsEntry("status", "ok").containsEntry("reauthenticate", true);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("activity_session=", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict");
        verify(auth).changePassword(principal, "old-password", "new-password");
    }

    @Test
    void resetRejectsAnAccountOutsideTheAuthorizedSingleOrganization() {
        var manager = principal("manager-1", "manager");
        when(auth.organizationOf(manager.id())).thenReturn("org-a");
        when(db.one(anyString(), any(Object[].class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.resetPassword(
                new UsernamePasswordAuthenticationToken(manager, "token"), "foreign-student",
                new ApiModels.PasswordResetRequest("reset-password")))
                .isInstanceOf(ApiException.class).hasMessage("无权重置该账号密码");

        verify(auth, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void allowedResetRevokesSessionsThroughThePasswordService() {
        var manager = principal("manager-1", "manager");
        when(auth.organizationOf(manager.id())).thenReturn("org-a");
        when(db.one(anyString(), any(Object[].class))).thenReturn(Optional.of(Map.of("allowed", 1)));

        var result = controller.resetPassword(new UsernamePasswordAuthenticationToken(manager, "token"), "student-1",
                new ApiModels.PasswordResetRequest("reset-password"));

        assertThat(result).containsEntry("force_password_change", true);
        verify(auth).setPassword("student-1", "reset-password", true);
        verify(db).one(contains("other_membership.organization_id<>om.organization_id"), eq("student-1"), eq("org-a"), eq(manager.id()));
    }

    @Test
    void participantCannotResetAnyAccountPassword() {
        var participant = principal("student-1", "participant");

        assertThatThrownBy(() -> controller.resetPassword(
                new UsernamePasswordAuthenticationToken(participant, "token"), "student-2",
                new ApiModels.PasswordResetRequest("reset-password")))
                .isInstanceOf(ApiException.class).hasMessage("只有管理者可以重置成员密码");

        verifyNoInteractions(db);
        verify(auth, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    private AccountPrincipal principal(String id, String role) {
        return new AccountPrincipal(id, id, "姓名", "", role, "active", "2026-10-25T10:00:00", 0);
    }
}

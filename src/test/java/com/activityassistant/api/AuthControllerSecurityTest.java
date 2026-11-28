package com.activityassistant.api;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.AccountAdministrationMapper;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AuditService;
import com.activityassistant.service.AuthService;
import com.activityassistant.service.StorageService;
import com.activityassistant.support.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthControllerSecurityTest {
    private final AuthService auth = mock(AuthService.class);
    private final AccountAdministrationMapper accounts = mock(AccountAdministrationMapper.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final AuthController controller = new AuthController(
            auth, accounts, properties, mock(AuditService.class), mock(StorageService.class), new ObjectMapper());

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
        when(accounts.lockResetTarget("foreign-student", "org-a", manager.id())).thenReturn(Optional.empty());

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
        when(accounts.lockResetTarget("student-1", "org-a", manager.id())).thenReturn(Optional.of(1));

        var result = controller.resetPassword(new UsernamePasswordAuthenticationToken(manager, "token"), "student-1",
                new ApiModels.PasswordResetRequest("reset-password"));

        assertThat(result).containsEntry("force_password_change", true);
        verify(auth).setPassword("student-1", "reset-password", true);
        verify(accounts).lockResetTarget("student-1", "org-a", manager.id());
    }

    @Test
    void participantCannotResetAnyAccountPassword() {
        var participant = principal("student-1", "participant");

        assertThatThrownBy(() -> controller.resetPassword(
                new UsernamePasswordAuthenticationToken(participant, "token"), "student-2",
                new ApiModels.PasswordResetRequest("reset-password")))
                .isInstanceOf(ApiException.class).hasMessage("只有管理者可以重置成员密码");

        verifyNoInteractions(accounts);
        verify(auth, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    private AccountPrincipal principal(String id, String role) {
        return new AccountPrincipal(id, id, "姓名", "", role, "active", "2026-10-25T10:00:00", 0);
    }
}

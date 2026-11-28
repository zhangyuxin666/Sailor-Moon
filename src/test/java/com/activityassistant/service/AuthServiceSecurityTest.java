package com.activityassistant.service;

import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.AuthMapper;
import java.util.Optional;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceSecurityTest {
    private final AuthMapper db = mock(AuthMapper.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final AuthService service = new AuthService(db, passwords, mock(AppProperties.class), mock(AuditService.class));
    private final AccountPrincipal account = new AccountPrincipal("student-1", "student", "学生", "1",
            "participant", "active", "2026-10-25T10:00:00", 1);

    @Test
    void changingPasswordClearsForcedChangeAndRevokesEveryDevice() {
        when(db.lockPassword(account.id())).thenReturn(Optional.of("old-hash"));
        when(passwords.verify("old-password", "old-hash")).thenReturn(true);
        when(passwords.hash("new-password")).thenReturn("new-hash");
        when(db.updateSecurity( eq(0), anyString(), eq(account.id()))).thenReturn(1);

        service.changePassword(account, "old-password", "new-password");

        verify(db).updatePassword("new-hash", account.id());
        verify(db).updateSecurity( eq(0), anyString(), eq(account.id()));
        verify(db).deleteAccountSessions(account.id());
        verify(db).lockPassword(account.id());
    }

    @Test
    void wrongCurrentPasswordDoesNotChangePasswordOrRevokeSessions() {
        when(db.lockPassword(account.id())).thenReturn(Optional.of("old-hash"));

        assertThatThrownBy(() -> service.changePassword(account, "wrong-password", "new-password"))
                .isInstanceOf(ApiException.class).hasMessage("当前密码错误");

        verify(passwords, never()).hash(anyString());
        verify(db, never()).deleteAccountSessions(account.id());
    }

    @Test
    void mandatoryPasswordChangeCannotBeSatisfiedWithTheSamePassword() {
        when(db.lockPassword(account.id())).thenReturn(Optional.of("old-hash"));
        when(passwords.verify("same-password", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(account, "same-password", "same-password"))
                .isInstanceOf(ApiException.class).hasMessage("新密码不能与当前密码相同");

        verify(passwords, never()).hash(anyString());
        verify(db, never()).deleteAccountSessions(account.id());
    }

    @Test
    void managerResetRequiresNewPasswordAndRevokesEverySession() {
        when(passwords.hash("reset-password")).thenReturn("reset-hash");
        when(db.updateSecurity( eq(1), anyString(), eq(account.id()))).thenReturn(1);

        service.setPassword(account.id(), "reset-password", true);

        verify(db).updatePassword("reset-hash", account.id());
        verify(db).updateSecurity( eq(1), anyString(), eq(account.id()));
        verify(db).deleteAccountSessions(account.id());
        verify(db, never()).createSecurity(anyString(), anyInt(), anyString());
    }

    @Test
    void resetOfLegacyAccountCreatesSecurityRecordAndStillRevokesSessions() {
        when(passwords.hash("reset-password")).thenReturn("reset-hash");

        service.setPassword(account.id(), "reset-password", true);

        verify(db).createSecurity(eq(account.id()), eq(1), anyString());
        verify(db).deleteAccountSessions(account.id());
    }
}

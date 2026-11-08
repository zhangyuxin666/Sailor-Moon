package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthRegistrationTest {
    private final RegistrationDb db = new RegistrationDb();
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final AuditService audits = mock(AuditService.class);
    private final AuthService service = new AuthService(db, passwords, properties, audits);

    private ApiModels.RegisterRequest request(String role, boolean consent) {
        return new ApiModels.RegisterRequest("new-user", "New User", "long-password", role, "New Valley", consent);
    }

    @Test void managerOwnsOnlyANewOrganizationAndGetsASession() {
        when(passwords.hash("long-password")).thenReturn("encoded-password");
        when(properties.sessionDays()).thenReturn(7);
        var result = service.register(request("manager", true));
        assertThat(result.account().role()).isEqualTo("manager");
        assertThat(result.account().forcePasswordChange()).isZero();
        assertThat(result.token()).isNotBlank();
        assertThat(db.accountArgs[4]).isEqualTo("encoded-password");
        assertThat(db.organizationArgs[2]).isEqualTo(result.account().id());
        assertThat(db.membershipArgs[0]).isEqualTo(db.organizationArgs[0]);
        assertThat(db.membershipArgs[1]).isEqualTo(result.account().id());
        assertThat(db.writes).anyMatch(s -> s.startsWith("INSERT INTO auth_sessions"));
        assertThat(db.writes).anyMatch(s -> s.startsWith("INSERT INTO privacy_consents"));
        assertThat(db.writes).noneMatch(s -> s.startsWith("UPDATE") || s.startsWith("DELETE"));
    }

    @Test void participantCannotSelectOrInheritAnOrganization() {
        var result = service.register(request("participant", true));
        assertThat(result.account().role()).isEqualTo("participant");
        assertThat(db.organizationArgs).isNull();
        assertThat(db.membershipArgs).isNull();
    }

    @Test void duplicateUsernameDoesNotWriteOrResetAnExistingPassword() {
        db.duplicate = true;
        assertThatThrownBy(() -> service.register(request("manager", true))).isInstanceOf(ApiException.class).hasMessageContaining("用户名");
        assertThat(db.writes).isEmpty();
        verifyNoInteractions(passwords);
    }

    @Test void consentIsRequiredBeforeAnyWrite() {
        assertThatThrownBy(() -> service.register(request("manager", false))).isInstanceOf(ApiException.class);
        assertThat(db.writes).isEmpty();
    }

    @Test void arbitraryPrivilegedOrGuestRolesAreRejected() {
        for (String role : List.of("admin", "owner", "guest")) {
            assertThatThrownBy(() -> service.register(request(role, true))).isInstanceOf(ApiException.class);
        }
        assertThat(db.writes).isEmpty();
    }

    @Test void registrationCannotBypassInitialBootstrap() {
        db.initialized = false;
        assertThatThrownBy(() -> service.register(request("participant", true))).isInstanceOf(ApiException.class).hasMessageContaining("初始化");
        assertThat(db.writes).isEmpty();
    }

    @Test void requestValidationRejectsWeakPasswordsBadNamesAndUnknownRoles() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request("manager", true))).isEmpty();
            assertThat(validator.validate(new ApiModels.RegisterRequest(" bad name ", "User", "short", "admin", "Org", true)))
                    .extracting(v -> v.getPropertyPath().toString()).contains("username", "password", "role");
        }
    }

    private static class RegistrationDb extends Db {
        boolean initialized = true, duplicate = false;
        List<String> writes = new ArrayList<>();
        Object[] accountArgs, organizationArgs, membershipArgs;
        RegistrationDb() { super(new JdbcTemplate()); }
        @Override public Optional<Map<String, Object>> one(String sql, Object... args) {
            if (sql.equals("SELECT 1 FROM accounts LIMIT 1")) return initialized ? Optional.of(Map.of("one", 1)) : Optional.empty();
            if (sql.equals("SELECT 1 FROM accounts WHERE username=?")) return duplicate ? Optional.of(Map.of("one", 1)) : Optional.empty();
            if (sql.startsWith("SELECT a.*, 0 AS force_password_change")) return Optional.of(Map.of(
                    "id", accountArgs[0], "username", accountArgs[1], "display_name", accountArgs[2],
                    "role", accountArgs[5], "status", "active", "created_at", accountArgs[6], "force_password_change", 0));
            throw new AssertionError(sql);
        }
        @Override public int update(String sql, Object... args) {
            writes.add(sql);
            if (sql.startsWith("INSERT INTO accounts ")) accountArgs = args;
            if (sql.startsWith("INSERT INTO organizations ")) organizationArgs = args;
            if (sql.startsWith("INSERT INTO organization_members ")) membershipArgs = args;
            return 1;
        }
    }
}

package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.mapper.AuthMapper;
import com.activityassistant.security.PasswordHasher;
import com.activityassistant.support.ApiException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

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
        assertThat(db.writes).anyMatch("createSession"::equals);
        assertThat(db.writes).anyMatch("recordConsent"::equals);
        assertThat(db.writes).noneMatch(s -> s.startsWith("update") || s.startsWith("delete"));
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

    private static class RegistrationDb implements AuthMapper {
        @Override public Map<String, Object> findAnyAccountRow() {
            return findAnyAccount().orElse(null);
        }

        @Override public Map<String, Object> findSessionRow(String tokenHash, String now) {
            return findSession(tokenHash, now).orElse(null);
        }

        @Override public Map<String, Object> findNewAccountRow(String accountId) {
            return findNewAccount(accountId).orElse(null);
        }

        @Override public Map<String, Object> findUsernameRow(String username) {
            return findUsername(username).orElse(null);
        }

        @Override public Map<String, Object> findLoginAccountRow(String username) {
            return findLoginAccount(username).orElse(null);
        }

        @Override public String lockPasswordRow(String accountId) {
            return lockPassword(accountId).orElse(null);
        }

        @Override public Map<String, Object> findOrganizationRow(String accountId) {
            return findOrganization(accountId).orElse(null);
        }

        boolean initialized = true, duplicate = false;
        List<String> writes = new ArrayList<>();
        Object[] accountArgs, organizationArgs, membershipArgs;
        @Override public Optional<Map<String, Object>> findAnyAccount() {
            return initialized ? Optional.of(Map.of("one", 1)) : Optional.empty();
        }

        @Override public Optional<Map<String, Object>> findSession(String tokenHash, String now) {
            throw new AssertionError("findSession");
        }

        @Override public void initializeSecurity(String accountId, String now) {
            writes.add("initializeSecurity");
        }

        @Override public void createOrganization(String id, String name, String ownerId, String now) {
            writes.add("createOrganization");
            organizationArgs = new Object[]{id, name, ownerId, now};
        }

        @Override public void grantOwner(String organizationId, String accountId, String now) {
            writes.add("grantOwner");
            membershipArgs = new Object[]{organizationId, accountId, now};
        }

        @Override public void recordConsent(String accountId, String now) {
            writes.add("recordConsent");
        }

        @Override public Optional<Map<String, Object>> findNewAccount(String accountId) {
            return Optional.of(Map.of("id", accountArgs[0], "username", accountArgs[1], "display_name", accountArgs[2],
                    "role", accountArgs[5], "status", "active", "created_at", accountArgs[6], "force_password_change", 0));
        }

        @Override public Optional<Map<String, Object>> findUsername(String username) {
            return duplicate ? Optional.of(Map.of("one", 1)) : Optional.empty();
        }

        @Override public Optional<Map<String, Object>> findLoginAccount(String username) {
            throw new AssertionError("findLoginAccount");
        }

        @Override public void createAccount(String id, String username, String displayName, String studentNo, String passwordHash, String role, String now) {
            writes.add("createAccount");
            accountArgs = new Object[]{id, username, displayName, studentNo, passwordHash, role, now};
        }

        @Override public void createSession(String tokenHash, String accountId, String expiresAt, String now) {
            writes.add("createSession");
        }

        @Override public void deleteSession(String tokenHash) {
            writes.add("deleteSession");
        }

        @Override public Optional<String> lockPassword(String accountId) {
            throw new AssertionError("lockPassword");
        }

        @Override public void updatePassword(String passwordHash, String accountId) {
            writes.add("updatePassword");
        }

        @Override public int updateSecurity(int forceChange, String now, String accountId) {
            writes.add("updateSecurity");
            return 1;
        }

        @Override public void createSecurity(String accountId, int forceChange, String now) {
            writes.add("createSecurity");
        }

        @Override public void deleteAccountSessions(String accountId) {
            writes.add("deleteAccountSessions");
        }

        @Override public Optional<Map<String, Object>> findOrganization(String accountId) {
            throw new AssertionError("findOrganization");
        }
    }
}

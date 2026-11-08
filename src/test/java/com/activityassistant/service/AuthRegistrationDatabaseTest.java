package com.activityassistant.service;

import com.activityassistant.api.ApiModels;
import com.activityassistant.config.AppProperties;
import com.activityassistant.persistence.Db;
import com.activityassistant.security.PasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Opt-in against a local PostgreSQL instance. Every inserted row is rolled back. */
@EnabledIfSystemProperty(named = "registration.test.jdbc", matches = ".+")
class AuthRegistrationDatabaseTest {
    @Test void registrationPersistsPermissionsAndCanLoginWithoutTouchingExistingUsers() {
        var dataSource = new DriverManagerDataSource(System.getProperty("registration.test.jdbc"),
                System.getProperty("registration.test.user", "activity"), System.getProperty("registration.test.password", "activity"));
        var db = new Db(new JdbcTemplate(dataSource));
        var properties = mock(AppProperties.class);
        when(properties.sessionDays()).thenReturn(7);
        var service = new AuthService(db, new PasswordHasher(), properties, new AuditService(db, new ObjectMapper()));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String prefix = "regtest-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            if (!service.initialized()) service.bootstrap(new ApiModels.BootstrapRequest(prefix + "-owner", "Test Owner", "test-password-123", "Test Organization", true));
            var manager = service.register(new ApiModels.RegisterRequest(prefix + "-manager", "Test Manager", "test-password-123", "manager", "Isolated Test Valley", true));
            var participant = service.register(new ApiModels.RegisterRequest(prefix + "-member", "Test Member", "test-password-123", "participant", "Ignored", true));
            assertThat(manager.account().isManager()).isTrue();
            assertThat(participant.account().isManager()).isFalse();
            String org = service.organizationOf(manager.account().id());
            assertThat(org).isNotBlank();
            assertThat(service.organizationOf(participant.account().id())).isNull();
            assertThat(db.one("SELECT 1 FROM organizations WHERE id=? AND owner_account_id=?", org, manager.account().id())).isPresent();
            assertThat(service.findByRawToken(manager.token())).isPresent();
            assertThat(service.findByRawToken(participant.token())).isPresent();
            var login = service.login(new ApiModels.LoginRequest(prefix + "-member", "test-password-123"), "registration-test");
            assertThat(login.account().role()).isEqualTo("participant");
            assertThat(login.account().forcePasswordChange()).isZero();
            assertThat(db.one("SELECT 1 FROM privacy_consents WHERE account_id=?", participant.account().id())).isPresent();
        });
        assertThat(db.one("SELECT 1 FROM accounts WHERE username LIKE ?", prefix + "%")).isEmpty();
    }
}

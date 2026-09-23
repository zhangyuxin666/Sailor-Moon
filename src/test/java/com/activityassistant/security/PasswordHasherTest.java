package com.activityassistant.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesHashesCreatedByTheLegacyPythonBackend() {
        String encoded = "pbkdf2_sha256$000102030405060708090a0b0c0d0e0f$" +
                "2d56700cd8fa8619ac5f7f2ac1964dca279ab85a90f0d2e8431cdd135234db9a";

        assertThat(hasher.verify("password123", encoded)).isTrue();
        assertThat(hasher.verify("wrong", encoded)).isFalse();
    }

    @Test
    void createsRoundTripHashes() {
        String encoded = hasher.hash("一个足够长的密码");
        assertThat(encoded).startsWith("pbkdf2_sha256$");
        assertThat(hasher.verify("一个足够长的密码", encoded)).isTrue();
    }
}

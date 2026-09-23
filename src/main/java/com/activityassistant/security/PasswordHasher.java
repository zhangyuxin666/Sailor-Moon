package com.activityassistant.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class PasswordHasher {
    private static final int ITERATIONS = 310_000;
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2_sha256$" + HexFormat.of().formatHex(salt) + "$" +
                HexFormat.of().formatHex(derive(password, salt));
    }

    public boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", 3);
            if (parts.length != 3 || !"pbkdf2_sha256".equals(parts[0])) return false;
            byte[] expected = HexFormat.of().parseHex(parts[2]);
            byte[] actual = derive(password, HexFormat.of().parseHex(parts[1]));
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算密码摘要", exception);
        }
    }
}

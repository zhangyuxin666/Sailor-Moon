package com.activityassistant.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers shared by chunk verification and download ETag. */
public final class Sha256 {
    private Sha256() {}

    public static String of(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    public static String of(Path file) {
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digestInput = new DigestInputStream(input, digest())) {
            byte[] buffer = new byte[8192];
            while (digestInput.read(buffer) != -1) {
                // reading updates the digest
            }
            return HexFormat.of().formatHex(digestInput.getMessageDigest().digest());
        } catch (IOException exception) {
            throw new IllegalStateException("读取文件失败", exception);
        }
    }

    public static boolean matches(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                actual.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}

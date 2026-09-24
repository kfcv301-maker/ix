package com.admin.common.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Password storage with transparent verification of pre-migration MD5 hashes. */
public final class PasswordHashes {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(11);
    private static final String BCRYPT_SHA256_PREFIX = "{bcrypt-sha256}";

    private PasswordHashes() {}

    public static String encode(String password) {
        return BCRYPT_SHA256_PREFIX + BCRYPT.encode(prehash(password));
    }

    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        if (storedHash.startsWith(BCRYPT_SHA256_PREFIX)) {
            return BCRYPT.matches(prehash(password), storedHash.substring(BCRYPT_SHA256_PREFIX.length()));
        }
        // Accept bcrypt hashes created by older/other installations as well.
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            return BCRYPT.matches(password, storedHash);
        }
        if (!isLegacyMd5(storedHash)) return false;
        String candidate = Md5Util.md5(password);
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean isLegacyMd5(String storedHash) {
        return storedHash != null && storedHash.matches("[0-9a-f]{32}");
    }

    private static String prehash(String password) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] bytes = ("flux-panel-password-v1:" + password).getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}

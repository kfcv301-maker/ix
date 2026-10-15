package com.admin.common.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Short-lived capabilities, never persistent node or DDNS credentials. */
@Service
public class NodeInstallBootstrapService {
    static final Duration LIFETIME = Duration.ofMinutes(10);
    static final int MAX_PENDING = 1024;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingInstall> pending = new HashMap<>();

    public NodeInstallBootstrapService() {
        this(Clock.systemUTC());
    }

    NodeInstallBootstrapService(Clock clock) {
        this.clock = clock;
    }

    public synchronized String issue(Long nodeId, Long issuerId, int tokenVersion,
                                     String panelUrl, String nodeSecret) {
        long now = clock.millis();
        pending.values().removeIf(value -> value.expiresAt() <= now || value.nodeId().equals(nodeId));
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("安装命令请求过多，请稍后重试");
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.put(hash(token), new PendingInstall(nodeId, issuerId, tokenVersion, panelUrl,
                hash(nodeSecret), now + LIFETIME.toMillis()));
        return token;
    }

    /** Removing under the same lock makes a capability claim atomic. */
    public synchronized PendingInstall claim(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{32}")) {
            return null;
        }
        PendingInstall install = pending.remove(hash(token));
        return install != null && install.expiresAt() > clock.millis() ? install : null;
    }

    public record PendingInstall(Long nodeId, Long issuerId, int tokenVersion, String panelUrl,
                                 String nodeSecretHash, long expiresAt) {
        public boolean matchesNodeSecret(String secret) {
            return secret != null && MessageDigest.isEqual(
                    nodeSecretHash.getBytes(StandardCharsets.US_ASCII),
                    hash(secret).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

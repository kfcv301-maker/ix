package com.admin.service.impl;

import com.admin.service.VpsTerminalTicketService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An opaque ticket is safer than putting a long-lived login JWT in a WebSocket
 * URL. Only a SHA-256 digest is retained in memory and consumption removes it
 * atomically, making a ticket both short-lived and single-use.
 */
@Service
public class VpsTerminalTicketServiceImpl implements VpsTerminalTicketService {

    private static final long TTL_MILLIS = 60_000L;
    private static final int MAX_OUTSTANDING_TICKETS = 2_048;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, TerminalAccess> tickets = new ConcurrentHashMap<>();

    @Override
    public IssuedTicket issue(Long userId, boolean administrator, Long vpsId) {
        if (userId == null || vpsId == null) {
            throw new IllegalArgumentException("无法创建 SSH 终端票据");
        }
        cleanupExpiredTickets();
        if (tickets.size() >= MAX_OUTSTANDING_TICKETS) {
            throw new IllegalStateException("终端请求过多，请稍后重试");
        }

        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        tickets.put(hash(value), new TerminalAccess(userId, administrator, vpsId, expiresAt));
        return new IssuedTicket(value, expiresAt);
    }

    @Override
    public TerminalAccess consume(String rawTicket) {
        if (rawTicket == null || rawTicket.length() < 32 || rawTicket.length() > 128) {
            return null;
        }
        TerminalAccess access = tickets.remove(hash(rawTicket));
        if (access == null || access.getExpiresAt() <= System.currentTimeMillis()) {
            return null;
        }
        return access;
    }

    private void cleanupExpiredTickets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, TerminalAccess>> iterator = tickets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TerminalAccess> entry = iterator.next();
            if (entry.getValue().getExpiresAt() <= now) {
                tickets.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保护 SSH 终端票据", exception);
        }
    }
}

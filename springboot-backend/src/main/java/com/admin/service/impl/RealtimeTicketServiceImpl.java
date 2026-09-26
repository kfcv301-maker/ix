package com.admin.service.impl;

import com.admin.service.RealtimeTicketService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps only ticket digests in memory so browser URLs never contain a JWT. */
@Service
public class RealtimeTicketServiceImpl implements RealtimeTicketService {

    private static final long TTL_MILLIS = 60_000L;
    private static final int MAX_OUTSTANDING = 2_048;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Access> tickets = new ConcurrentHashMap<>();

    @Override
    public IssuedTicket issue(Long userId) {
        if (userId == null) throw new IllegalArgumentException("无法创建实时监控票据");
        cleanup();
        if (tickets.size() >= MAX_OUTSTANDING) throw new IllegalStateException("实时监控请求过多，请稍后重试");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        tickets.put(hash(value), new Access(userId, expiresAt));
        return new IssuedTicket(value, expiresAt);
    }

    @Override
    public Access consume(String rawTicket) {
        if (rawTicket == null || rawTicket.length() < 32 || rawTicket.length() > 128) return null;
        Access access = tickets.remove(hash(rawTicket));
        return access == null || access.getExpiresAt() <= System.currentTimeMillis() ? null : access;
    }

    @Override
    public void revokeUserTickets(Long userId) {
        if (userId != null) tickets.entrySet().removeIf(entry -> userId.equals(entry.getValue().getUserId()));
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().getExpiresAt() <= now);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保护实时监控票据", exception);
        }
    }
}

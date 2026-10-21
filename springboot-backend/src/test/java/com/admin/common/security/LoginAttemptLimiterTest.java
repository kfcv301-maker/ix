package com.admin.common.security;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    @Test void usernameLimitAppliesAcrossSourcesCaseAndAccents() {
        var limiter = new LoginAttemptLimiter(clock, 2, 10, 100);
        assertTrue(limiter.tryAcquire("Alice", "source1"));
        assertTrue(limiter.tryAcquire("ÁLICE", "source2"));
        assertFalse(limiter.tryAcquire("alice", "source3"));
        assertTrue(limiter.tryAcquire("bob", "source3"));
    }

    @Test void sourceLimitPreventsUsernameRotationAndExpires() {
        var limiter = new LoginAttemptLimiter(clock, 10, 2, 100);
        assertTrue(limiter.tryAcquire("alice", "source"));
        assertTrue(limiter.tryAcquire("bob", "source"));
        assertFalse(limiter.tryAcquire("charlie", "source"));
        now.addAndGet(60_000);
        assertTrue(limiter.tryAcquire("charlie", "source"));
    }

    @Test void fullRegistryRejectsNewEntriesWithoutEvictingActiveLimits() {
        var limiter = new LoginAttemptLimiter(clock, 1, 20, 3);
        assertTrue(limiter.tryAcquire("alice", "source"));
        assertTrue(limiter.tryAcquire("bob", "source"));
        assertFalse(limiter.tryAcquire("charlie", "source"));
        assertFalse(limiter.tryAcquire("alice", "different-source"));
        now.addAndGet(60_000);
        assertTrue(limiter.tryAcquire("charlie", "source"));
    }
}

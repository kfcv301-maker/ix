package com.admin.common.security;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded login throttling independent of attacker-supplied forwarding headers. */
@Component
public class LoginAttemptLimiter {
    private static final long WINDOW_MILLIS = 60_000;
    private final Clock clock;
    private final int userLimit;
    private final int sourceLimit;
    private final int capacity;
    private final Map<String, Window> windows = new HashMap<>();
    private long nextCleanup;

    public LoginAttemptLimiter() { this(Clock.systemUTC(), 10, 60, 4096); }

    LoginAttemptLimiter(Clock clock, int userLimit, int sourceLimit, int capacity) {
        this.clock = clock; this.userLimit = userLimit; this.sourceLimit = sourceLimit; this.capacity = capacity;
    }

    public synchronized boolean tryAcquire(String username, String remoteAddress) {
        long now = clock.millis();
        if (now >= nextCleanup) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            nextCleanup = now + WINDOW_MILLIS;
        }
        String user = Normalizer.normalize(username == null ? "" : username.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        String[] keys = {"user:" + user, "source:" + remoteAddress};
        int[] limits = {userLimit, sourceLimit};
        int missing = 0;
        for (int index = 0; index < keys.length; index++) {
            Window current = windows.get(keys[index]);
            if (current == null) missing++;
            else if (current.expiresAt > now && current.count >= limits[index]) return false;
        }
        if (windows.size() + missing > capacity) return false;
        for (String key : keys) {
            Window current = windows.get(key);
            if (current == null || current.expiresAt <= now) {
                current = new Window(now + WINDOW_MILLIS); windows.put(key, current);
            }
            current.count++;
        }
        return true;
    }

    private static final class Window {
        private final long expiresAt;
        private int count;
        private Window(long expiresAt) { this.expiresAt = expiresAt; }
    }
}

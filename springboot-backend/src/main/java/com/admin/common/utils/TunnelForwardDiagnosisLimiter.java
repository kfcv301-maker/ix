package com.admin.common.utils;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds expensive, node-side tunnel diagnostics. Individual jobs use a small
 * probe concurrency, while this limiter keeps only a few jobs active globally
 * and prevents one actor from repeatedly starting the same tunnel scan.
 */
@Component
public class TunnelForwardDiagnosisLimiter {

    private static final int MAX_CONCURRENT_DIAGNOSTICS = 2;
    private static final long COOLDOWN_MILLIS = 30_000L;
    private static final long HISTORY_RETENTION_MILLIS = 10 * 60_000L;

    private final Semaphore capacity = new Semaphore(MAX_CONCURRENT_DIAGNOSTICS);
    private final Map<String, Long> running = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStarted = new ConcurrentHashMap<>();

    public Lease acquire(Integer userId, Long tunnelId) {
        if (userId == null || tunnelId == null) {
            throw new IllegalArgumentException("无法确定诊断请求归属");
        }
        long now = System.currentTimeMillis();
        String key = userId + ":" + tunnelId;
        Long previousStart = lastStarted.get(key);
        if (previousStart != null && now - previousStart < COOLDOWN_MILLIS) {
            long remainingSeconds = Math.max(1, (COOLDOWN_MILLIS - (now - previousStart) + 999) / 1000);
            throw new IllegalStateException("该隧道刚执行过一键 PING，请 " + remainingSeconds + " 秒后再试");
        }
        Lease lease = acquireSlot(key, now);
        lastStarted.put(key, now);
        cleanupHistory(now);
        return lease;
    }

    /** Single-rule probes share the batch capacity but do not impose a tunnel cooldown. */
    public Lease acquireForward(Integer userId, Long forwardId) {
        if (userId == null || forwardId == null) throw new IllegalArgumentException("无法确定诊断请求归属");
        return acquireSlot("forward:" + forwardId, System.currentTimeMillis());
    }

    private Lease acquireSlot(String key, long now) {
        if (running.putIfAbsent(key, now) != null) {
            throw new IllegalStateException("该检测正在执行中，请稍后重试");
        }
        if (!capacity.tryAcquire()) {
            running.remove(key);
            throw new IllegalStateException("当前诊断任务较多，请稍后重试");
        }
        return new Lease(key);
    }

    private void cleanupHistory(long now) {
        for (Map.Entry<String, Long> entry : lastStarted.entrySet()) {
            if (now - entry.getValue() > HISTORY_RETENTION_MILLIS) {
                lastStarted.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            running.remove(key);
            capacity.release();
        }
    }
}

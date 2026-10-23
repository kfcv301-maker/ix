package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

class TunnelForwardDiagnosisLimiterTest {
    @Test void singleRulesAndBatchesShareCapacityWithoutSingleRuleTunnelCooldown() {
        var limiter = new TunnelForwardDiagnosisLimiter();
        try (var first = limiter.acquireForward(7, 1L); var batch = limiter.acquire(8, 12L)) {
            assertThrows(IllegalStateException.class, () -> limiter.acquireForward(7, 2L));
            assertThrows(IllegalStateException.class, () -> limiter.acquireForward(8, 1L),
                    "another authorized operator must not duplicate the same rule probe");
        }
        try (var next = limiter.acquireForward(7, 2L); var previous = limiter.acquireForward(7, 1L)) {
            assertNotNull(next); assertNotNull(previous);
        }
    }

    @Test void simultaneousRepeatedReleaseCannotIncreaseTheGlobalCapacity() throws Exception {
        var limiter = new TunnelForwardDiagnosisLimiter();
        var lease = limiter.acquireForward(7, 1L);
        CountDownLatch ready = new CountDownLatch(1);
        Runnable close = () -> {
            try { ready.await(); lease.close(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        };
        Thread first = new Thread(close); Thread second = new Thread(close);
        first.start(); second.start(); ready.countDown(); first.join(2000); second.join(2000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        try (var a = limiter.acquireForward(7, 1L); var b = limiter.acquireForward(7, 2L)) {
            assertThrows(IllegalStateException.class, () -> limiter.acquireForward(7, 3L));
        }
    }
}

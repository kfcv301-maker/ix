package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserMetricOutboxTest {
    @Test
    void slowWriteCoalescesLatestNodeSnapshotWithoutBlockingProducers() throws Exception {
        WebSocketSession browser = browser("slow");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        try (BrowserMetricOutbox outbox = new BrowserMetricOutbox(1, 4, 3, 1000, 2000,
                (session, nodeId) -> true, (session, payload) -> {
                    delivered.add(payload);
                    if ("first".equals(payload)) {
                        writing.countDown();
                        await(release);
                    }
                }, session -> fail("The bounded coalescing workload must not retire the browser"))) {
            outbox.offer(browser, 1L, 1, "first", null);
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                outbox.offer(browser, 1L, 2, "detail-2", null);
                outbox.offer(browser, 1L, 3, "summary-3", () -> "merged-snapshot-3");
                outbox.offer(browser, 1L, 2, "stale-snapshot-2", null);
            });
            release.countDown();
            eventually(() -> delivered.size() == 2);
            assertEquals(List.of("first", "merged-snapshot-3"), delivered);
        } finally {
            release.countDown();
        }
    }

    @Test
    void stalledBrowserIsRetiredAndClosedByWatchdogOutsideProducerThread() throws Exception {
        WebSocketSession browser = browser("stalled");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        try (BrowserMetricOutbox outbox = new BrowserMetricOutbox(1, 2, 3, 1000, 100,
                (session, nodeId) -> true, (session, payload) -> {
                    writing.countDown();
                    await(release);
                }, session -> retired.countDown())) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> outbox.offer(browser, 1L, 1, "first", null));
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            assertTrue(retired.await(1, TimeUnit.SECONDS));
            verify(browser, timeout(1000)).close(CloseStatus.SESSION_NOT_RELIABLE);
        } finally {
            release.countDown();
        }
    }

    @Test
    void permissionRevokedAfterEnqueueIsCheckedAgainBeforeActualWrite() throws Exception {
        WebSocketSession browser = browser("revoked");
        AtomicBoolean authorized = new AtomicBoolean(true);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        try (BrowserMetricOutbox outbox = new BrowserMetricOutbox(1, 2, 3, 1000, 2000,
                (session, nodeId) -> !Long.valueOf(2).equals(nodeId) || authorized.get(), (session, payload) -> {
                    delivered.add(payload);
                    writing.countDown();
                    await(release);
                }, session -> { })) {
            outbox.offer(browser, 1L, 1, "first", null);
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            outbox.offer(browser, 2L, 2, "must-not-leak", null);
            authorized.set(false);
            release.countDown();
            // A second browser is a barrier through the same single worker.
            WebSocketSession barrier = browser("barrier");
            outbox.offer(barrier, 3L, 3, "barrier", null);
            eventually(() -> delivered.contains("barrier"));
            assertFalse(delivered.contains("must-not-leak"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void pendingNodeOrByteOverflowRetiresBrowserInsteadOfGrowingAnUnboundedQueue() throws Exception {
        WebSocketSession browser = browser("overflow");
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        try (BrowserMetricOutbox outbox = new BrowserMetricOutbox(1, 2, 1, 100, 2000,
                (session, nodeId) -> true, (session, payload) -> {
                    writing.countDown();
                    await(release);
                }, session -> retired.countDown())) {
            outbox.offer(browser, 1L, 1, "first", null);
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            outbox.offer(browser, 2L, 2, "pending", null);
            outbox.offer(browser, 3L, 3, "exceeds-one-pending-node", null);
            assertTrue(retired.await(1, TimeUnit.SECONDS));
            verify(browser, timeout(1000)).close(CloseStatus.SESSION_NOT_RELIABLE);
        } finally {
            release.countDown();
        }
    }

    private WebSocketSession browser(String id) {
        WebSocketSession browser = mock(WebSocketSession.class);
        when(browser.getId()).thenReturn(id);
        when(browser.isOpen()).thenReturn(true);
        return browser;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release the browser write");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void eventually(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(5);
        });
    }
}

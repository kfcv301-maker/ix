package com.admin.common.utils;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Browser I/O must never run on an Agent's inbound command/heartbeat thread.
 * Each browser owns at most one worker task and one latest event per node.
 * Limits reject/retire the browser rather than silently accumulate work.
 */
final class BrowserMetricOutbox implements AutoCloseable {
    private record Pending(Long nodeId, long sequence, String payload) { }
    private static final class Mailbox {
        final WebSocketSession session;
        final LinkedHashMap<Long, Pending> pending = new LinkedHashMap<>();
        boolean scheduled;
        volatile boolean closed;
        long pendingBytes;
        volatile long sendingSince;
        volatile long lastProgress = System.nanoTime();
        Mailbox(WebSocketSession session) { this.session = session; }
    }

    private final Map<String, Mailbox> mailboxes = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor closers;
    private final ScheduledExecutorService watchdog;
    private final BiPredicate<WebSocketSession, Long> allowed;
    private final BiConsumer<WebSocketSession, String> send;
    private final Consumer<WebSocketSession> retire;
    private final int maximumBrowsers;
    private final int maximumPendingNodes;
    private final long maximumPendingBytes;
    private final long sendTimeoutNanos;

    BrowserMetricOutbox(BiPredicate<WebSocketSession, Long> allowed,
                        BiConsumer<WebSocketSession, String> send, Consumer<WebSocketSession> retire) {
        this(4, 256, 1024, 2 * 1024 * 1024L, 3000, allowed, send, retire);
    }

    BrowserMetricOutbox(int workerCount, int maximumBrowsers, int maximumPendingNodes,
                        long maximumPendingBytes, long timeoutMillis,
                        BiPredicate<WebSocketSession, Long> allowed,
                        BiConsumer<WebSocketSession, String> send, Consumer<WebSocketSession> retire) {
        this.maximumBrowsers = maximumBrowsers;
        this.maximumPendingNodes = maximumPendingNodes;
        this.maximumPendingBytes = maximumPendingBytes;
        this.sendTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.allowed = allowed;
        this.send = send;
        this.retire = retire;
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumBrowsers), daemonThreads("browser-metrics-"), new ThreadPoolExecutor.AbortPolicy());
        // Closing a broken transport may itself block. It has a separate small,
        // bounded executor; neither Agent readers nor the watchdog perform I/O.
        closers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumBrowsers), daemonThreads("browser-metrics-close-"), new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(daemonThreads("browser-metrics-watchdog-"));
        watchdog.scheduleWithFixedDelay(this::expireStalledBrowsers,
                Math.min(timeoutMillis, 250), Math.min(timeoutMillis, 250), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger nextId = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + nextId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    void offer(WebSocketSession session, Long nodeId, long sequence, String payload, Supplier<String> coalescedPayload) {
        if (!session.isOpen() || !allowed.test(session, nodeId)) return;
        Mailbox mailbox = mailboxes.get(session.getId());
        if (mailbox == null) {
            synchronized (mailboxes) {
                mailbox = mailboxes.get(session.getId());
                if (mailbox == null && mailboxes.size() < maximumBrowsers) {
                    mailbox = new Mailbox(session);
                    mailboxes.put(session.getId(), mailbox);
                }
            }
            if (mailbox == null) {
                retireAndClose(session);
                return;
            }
        }
        boolean schedule = false;
        boolean overflow = false;
        synchronized (mailbox) {
            if (mailbox.closed) return;
            Pending previous = mailbox.pending.get(nodeId);
            if (previous != null && previous.sequence() > sequence) return;
            // A summary overtaking an unsent detail/snapshot must retain that
            // detail. The supplied latest snapshot is already permission-scoped.
            String nextPayload = previous != null && coalescedPayload != null ? coalescedPayload.get() : payload;
            long bytes = mailbox.pendingBytes - (previous == null ? 0 : previous.payload().length() * 2L)
                    + nextPayload.length() * 2L;
            if ((previous == null && mailbox.pending.size() >= maximumPendingNodes) || bytes > maximumPendingBytes) {
                overflow = true;
            } else {
                mailbox.pending.put(nodeId, new Pending(nodeId, sequence, nextPayload));
                mailbox.pendingBytes = bytes;
                if (!mailbox.scheduled) {
                    mailbox.scheduled = true;
                    mailbox.lastProgress = System.nanoTime();
                    schedule = true;
                }
            }
        }
        if (overflow) discard(mailbox);
        else if (schedule) schedule(mailbox);
    }

    private void schedule(Mailbox mailbox) {
        try {
            workers.execute(() -> drain(mailbox));
        } catch (RejectedExecutionException saturated) {
            discard(mailbox);
        }
    }

    private void drain(Mailbox mailbox) {
        // Yield between batches so one busy dashboard cannot monopolize a worker.
        for (int sent = 0; sent < 32; sent++) {
            Pending event;
            synchronized (mailbox) {
                if (mailbox.closed) return;
                if (mailbox.pending.isEmpty()) {
                    mailbox.scheduled = false;
                    return;
                }
                var iterator = mailbox.pending.entrySet().iterator();
                event = iterator.next().getValue();
                iterator.remove();
                mailbox.pendingBytes -= event.payload().length() * 2L;
            }
            // Cached access is not authorization: expiry/revocation is checked
            // again immediately before every actual browser write.
            if (!mailbox.session.isOpen() || !allowed.test(mailbox.session, event.nodeId())) continue;
            if (mailbox.closed) return;
            mailbox.sendingSince = System.nanoTime();
            try {
                send.accept(mailbox.session, event.payload());
            } catch (RuntimeException failure) {
                discard(mailbox);
                return;
            } finally {
                mailbox.sendingSince = 0;
                mailbox.lastProgress = System.nanoTime();
            }
        }
        schedule(mailbox);
    }

    private void expireStalledBrowsers() {
        long now = System.nanoTime();
        for (Mailbox mailbox : mailboxes.values()) {
            long sendingSince = mailbox.sendingSince;
            boolean stalled = sendingSince != 0 && now - sendingSince >= sendTimeoutNanos;
            synchronized (mailbox) {
                stalled |= mailbox.scheduled && now - mailbox.lastProgress >= sendTimeoutNanos * 2;
            }
            if (stalled) discard(mailbox);
        }
    }

    void remove(WebSocketSession session) {
        Mailbox mailbox = mailboxes.remove(session.getId());
        if (mailbox != null) {
            synchronized (mailbox) {
                mailbox.closed = true;
                mailbox.pending.clear();
                mailbox.pendingBytes = 0;
            }
        }
    }

    private void discard(Mailbox mailbox) {
        if (!mailboxes.remove(mailbox.session.getId(), mailbox)) return;
        synchronized (mailbox) {
            mailbox.closed = true;
            mailbox.pending.clear();
            mailbox.pendingBytes = 0;
        }
        retireAndClose(mailbox.session);
    }

    void discard(WebSocketSession session) {
        Mailbox mailbox = mailboxes.get(session.getId());
        if (mailbox != null) discard(mailbox);
        else retireAndClose(session);
    }

    private void retireAndClose(WebSocketSession session) {
        retire.accept(session);
        try {
            closers.execute(() -> {
                try {
                    if (session.isOpen()) session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception ignored) {
                    // The browser is already retired; a broken close cannot
                    // re-enqueue it or block Agent acknowledgement threads.
                }
            });
        } catch (RejectedExecutionException saturated) {
            // Retirement still releases all buffers and prevents new writes.
        }
    }

    @Override
    public void close() {
        for (Mailbox mailbox : new ArrayList<>(mailboxes.values())) remove(mailbox.session);
        watchdog.shutdownNow();
        workers.shutdownNow();
        closers.shutdownNow();
    }
}

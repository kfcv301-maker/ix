package com.admin.common.utils;

import com.admin.service.NodeService;
import com.admin.entity.Node;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.admin.common.dto.GostDto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSocketInitialSnapshotTest {
    private WebSocketServer server;

    @BeforeEach
    void setUp() {
        clearState();
        server = new WebSocketServer();
        ReflectionTestUtils.setField(server, "nodeService", mock(NodeService.class));
    }

    @AfterEach
    void clearState() {
        Set<?> browsers = (Set<?>) ReflectionTestUtils.getField(WebSocketServer.class, "activeSessions");
        BrowserMetricOutbox outbox = (BrowserMetricOutbox) ReflectionTestUtils.getField(WebSocketServer.class, "browserOutbox");
        for (Object browser : browsers) outbox.remove((WebSocketSession) browser);
        browsers.clear();
        for (String field : List.of("nodeSessions", "latestNodeMetrics", "sessionLocks")) {
            ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, field)).clear();
        }
    }

    @Test
    void firstConnectionReceivesLatestCountersWithPreviousDetailedSampleWithoutAnotherAgentCommand() throws Exception {
        WebSocketSession agent = agent(1L, "agent-1");
        report(agent, "{\"metrics_level\":\"detail\",\"bytes_received\":100,\"bytes_transmitted\":200,\"cpu_usage\":10,\"memory_usage\":20,\"tcp_connections\":7,\"cpu_cores\":8,\"mux_metrics_enabled\":false}");
        report(agent, "{\"metrics_level\":\"summary\",\"bytes_received\":300,\"bytes_transmitted\":500,\"cpu_usage\":30,\"memory_usage\":40}");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("browser", "detail", Set.of(1L), messages));
        awaitReady(messages);

        JSONObject event = info(messages).getFirst();
        JSONObject data = event.getJSONObject("data");
        assertEquals(300, data.getIntValue("bytes_received"));
        assertEquals(7, data.getIntValue("tcp_connections"));
        assertEquals(8, data.getIntValue("cpu_cores"));
        assertFalse(data.getBooleanValue("mux_metrics_enabled"));
        assertTrue(event.getBooleanValue("snapshot"));
        assertTrue(event.getLongValue("reportedAt") > 0);
        assertTrue(event.getLongValue("sequence") > 0);
        assertEquals("ready", messages.getLast().getString("type"));
        // Existing heartbeat ACKs only: browser connects never trigger a metric scan.
        verify(agent, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void snapshotsAndLegacyReportsKeepSummaryWhitelistAndNodePermissions() throws Exception {
        WebSocketSession first = agent(1L, "first");
        WebSocketSession second = agent(2L, "second");
        String legacy = "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3,\"host_ip\":\"private-ip\",\"tcp_connections\":123,\"agent_rss\":999}";
        report(first, legacy);
        report(second, legacy);
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("assigned", "summary", Set.of(1L), messages));
        awaitReady(messages);
        assertEquals(1, info(messages).size());
        assertEquals(1L, info(messages).getFirst().getLongValue("id"));
        JSONObject summary = info(messages).getFirst().getJSONObject("data");
        assertEquals("summary", summary.getString("metrics_level"));
        for (String key : List.of("host_ip", "tcp_connections", "agent_rss", "memory_usage")) assertFalse(summary.containsKey(key));

        messages.clear();
        report(first, legacy);
        await(() -> info(messages).size() == 1);
        assertEquals(1, info(messages).size());
        assertFalse(info(messages).getFirst().getJSONObject("data").containsKey("tcp_connections"));
        report(second, legacy);
        assertEquals(1, info(messages).size());

        List<JSONObject> administratorMessages = new CopyOnWriteArrayList<>();
        WebSocketSession administrator = browser("administrator", "detail", Set.of(), administratorMessages);
        administrator.getAttributes().put("roleId", 0);
        server.afterConnectionEstablished(administrator);
        awaitReady(administratorMessages);
        assertEquals(2, info(administratorMessages).size());
    }

    @Test
    void liveSummariesStayLightweightAndNewDetailedReportClearsOldOptionalValues() throws Exception {
        WebSocketSession agent = agent(1L, "agent");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("monitor", "detail", Set.of(1L), messages));
        awaitReady(messages);
        report(agent, "{\"metrics_level\":\"detail\",\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3,\"tcp_connections\":123}");
        await(() -> info(messages).size() == 1);
        report(agent, "{\"metrics_level\":\"summary\",\"bytes_received\":2,\"bytes_transmitted\":3,\"cpu_usage\":4}");
        await(() -> info(messages).size() == 2);
        assertFalse(info(messages).getLast().getJSONObject("data").containsKey("tcp_connections"));
        report(agent, "{\"bytes_received\":3,\"bytes_transmitted\":4,\"cpu_usage\":5}");
        List<JSONObject> replay = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("replay", "detail", Set.of(1L), replay));
        awaitReady(replay);
        assertFalse(info(replay).getFirst().getJSONObject("data").containsKey("tcp_connections"));
    }

    @Test
    void staleAgentCannotOverwriteNewSnapshotAndDisconnectOrDeletionRemovesCachedMetrics() throws Exception {
        WebSocketSession oldAgent = agent(1L, "old");
        report(oldAgent, "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3}");
        WebSocketSession current = agent(1L, "current");
        report(current, "{\"bytes_received\":100,\"bytes_transmitted\":200,\"cpu_usage\":30}");
        report(oldAgent, "{\"bytes_received\":9,\"bytes_transmitted\":9,\"cpu_usage\":9}");
        server.afterConnectionClosed(oldAgent, CloseStatus.NORMAL);
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("new-browser", "detail", Set.of(1L), messages));
        awaitReady(messages);
        assertEquals(100, info(messages).getFirst().getJSONObject("data").getIntValue("bytes_received"));
        server.afterConnectionClosed(current, CloseStatus.NORMAL);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "latestNodeMetrics")).isEmpty());

        WebSocketSession next = agent(1L, "next");
        report(next, "{\"bytes_received\":10,\"bytes_transmitted\":20,\"cpu_usage\":3}");
        WebSocketServer.closeNodeSession(1L);
        List<JSONObject> afterDeletion = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("after-delete", "detail", Set.of(1L), afterDeletion));
        awaitReady(afterDeletion);
        assertTrue(info(afterDeletion).isEmpty());
    }

    @Test
    void expiredBrowserGrantReceivesNoSnapshot() throws Exception {
        WebSocketSession agent = agent(1L, "agent");
        report(agent, "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3}");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        WebSocketSession browser = browser("expired", "summary", Set.of(1L), messages);
        browser.getAttributes().put("monitorAccessExpiresAt", System.currentTimeMillis() - 1);
        server.afterConnectionEstablished(browser);
        assertTrue(info(messages).isEmpty());
        verify(browser, timeout(2000)).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void offlineStatusIsNewerThanAnySnapshotAndClearsTheCache() throws Exception {
        WebSocketSession agent = agent(1L, "agent");
        report(agent, "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3}");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("browser", "detail", Set.of(1L), messages));
        awaitReady(messages);
        long metricSequence = info(messages).getFirst().getLongValue("sequence");
        Node node = new Node();
        node.setId(1L);
        NodeService nodeService = (NodeService) ReflectionTestUtils.getField(server, "nodeService");
        when(nodeService.getById(1L)).thenReturn(node);
        when(nodeService.updateById(any(Node.class))).thenReturn(true);
        server.afterConnectionClosed(agent, CloseStatus.NORMAL);
        await(() -> "status".equals(messages.getLast().getString("type")));
        JSONObject status = messages.getLast();
        assertEquals("status", status.getString("type"));
        assertEquals(0, status.getIntValue("data"));
        assertTrue(status.getLongValue("sequence") > metricSequence);
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "latestNodeMetrics")).isEmpty());
    }

    @Test
    void blockedBrowserWriteDoesNotDelayTheSameAgentsNextHeartbeatAcknowledgement() throws Exception {
        WebSocketSession agent = agent(1L, "agent");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        WebSocketSession slow = browser("slow-browser", "detail", Set.of(1L), messages);
        server.afterConnectionEstablished(slow);
        awaitReady(messages);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(slow).sendMessage(any(TextMessage.class));
        try (var agentReader = Executors.newSingleThreadExecutor()) {
            report(agent, "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3}");
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            var next = agentReader.submit(() -> report(agent,
                    "{\"bytes_received\":2,\"bytes_transmitted\":3,\"cpu_usage\":4}"));
            next.get(1, TimeUnit.SECONDS);
            verify(agent, times(2)).sendMessage(any(TextMessage.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void retiredNodeStopsMetricsImmediatelyWhileCleanupCommandResponsesStillComplete() throws Exception {
        WebSocketSession agent = agent(1L, "retired-agent");
        List<JSONObject> messages = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("monitor", "detail", Set.of(1L), messages));
        awaitReady(messages);
        report(agent, "{\"bytes_received\":1,\"bytes_transmitted\":2,\"cpu_usage\":3}");
        await(() -> info(messages).size() == 1);
        WebSocketServer.retireNodeMetrics(1L);
        await(() -> "status".equals(messages.getLast().getString("type")));
        assertEquals(0, messages.getLast().getIntValue("data"));
        report(agent, "{\"bytes_received\":10,\"bytes_transmitted\":20,\"cpu_usage\":30}");
        CompletableFuture<GostDto> response = new CompletableFuture<>();
        Map<String, CompletableFuture<GostDto>> pending = (Map<String, CompletableFuture<GostDto>>)
                ReflectionTestUtils.getField(WebSocketServer.class, "pendingRequests");
        pending.put("cleanup-retired", response);
        report(agent, "{\"requestId\":\"cleanup-retired\",\"message\":\"OK\"}");
        assertEquals("OK", response.get(1, TimeUnit.SECONDS).getMsg());
        assertSame(agent, ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions")).get(1L));
        assertTrue(((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "latestNodeMetrics")).isEmpty());
        List<JSONObject> next = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("next", "detail", Set.of(1L), next));
        awaitReady(next);
        assertTrue(info(next).isEmpty());
        assertEquals(1, info(messages).size());
    }

    @Test
    void oldCloseCannotWriteOfflineAfterReplacementConnectionWritesOnline() throws Exception {
        long nodeId = 1000L;
        WebSocketSession old = agent(nodeId, "old-lifecycle");
        WebSocketSession replacement = session("replacement-lifecycle", new HashMap<>(Map.of("id", nodeId, "type", "1")));
        NodeService nodeService = (NodeService) ReflectionTestUtils.getField(server, "nodeService");
        when(nodeService.getById(nodeId)).thenAnswer(invocation -> {
            Node node = new Node();
            node.setId(nodeId);
            return node;
        });
        CountDownLatch writingOffline = new CountDownLatch(1);
        CountDownLatch releaseOffline = new CountDownLatch(1);
        AtomicInteger databaseStatus = new AtomicInteger(1);
        when(nodeService.updateById(any(Node.class))).thenAnswer(invocation -> {
            int status = ((Node) invocation.getArgument(0)).getStatus();
            if (status == 0) {
                writingOffline.countDown();
                assertTrue(releaseOffline.await(2, TimeUnit.SECONDS));
            }
            databaseStatus.set(status);
            return true;
        });
        List<JSONObject> events = new CopyOnWriteArrayList<>();
        server.afterConnectionEstablished(browser("lifecycle-monitor", "detail", Set.of(nodeId), events));
        awaitReady(events);
        try (var lifecycleThreads = Executors.newFixedThreadPool(2)) {
            var closing = lifecycleThreads.submit(() -> server.afterConnectionClosed(old, CloseStatus.NORMAL));
            assertTrue(writingOffline.await(1, TimeUnit.SECONDS));
            var opening = lifecycleThreads.submit(() -> server.afterConnectionEstablished(replacement));
            assertThrows(TimeoutException.class, () -> opening.get(100, TimeUnit.MILLISECONDS));
            releaseOffline.countDown();
            closing.get(1, TimeUnit.SECONDS);
            opening.get(1, TimeUnit.SECONDS);
            assertEquals(1, databaseStatus.get());
            await(() -> events.stream().anyMatch(event -> "status".equals(event.getString("type")) && event.getIntValue("data") == 1));
            JSONObject online = events.getLast();
            assertEquals(1, online.getIntValue("data"));
            assertEquals("replacement-lifecycle", online.getString("connectionId"));
            assertSame(replacement, ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions")).get(nodeId));
            server.afterConnectionClosed(old, CloseStatus.NORMAL);
            assertEquals(1, databaseStatus.get());
        } finally {
            releaseOffline.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    private WebSocketSession agent(Long nodeId, String sessionId) {
        WebSocketSession session = session(sessionId, new HashMap<>(Map.of("id", nodeId, "type", "1")));
        ((Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions")).put(nodeId, session);
        return session;
    }

    private WebSocketSession browser(String sessionId, String metrics, Set<Long> allowed, List<JSONObject> messages) throws Exception {
        WebSocketSession session = session(sessionId, new HashMap<>(Map.of("id", 42L, "type", "0", "roleId", 1,
                "monitorMetrics", metrics, "allowedNodeIds", allowed)));
        doAnswer(invocation -> {
            messages.add(JSONObject.parseObject(((TextMessage) invocation.getArgument(0)).getPayload()));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private WebSocketSession session(String id, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private void report(WebSocketSession source, String payload) {
        server.handleTextMessage(source, new TextMessage(payload));
    }

    private List<JSONObject> info(List<JSONObject> messages) {
        return messages.stream().filter(message -> "info".equals(message.getString("type"))).toList();
    }

    private void awaitReady(List<JSONObject> messages) {
        await(() -> messages.stream().anyMatch(message -> "ready".equals(message.getString("type"))));
    }

    private void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (!condition.getAsBoolean()) Thread.sleep(5);
        });
    }
}

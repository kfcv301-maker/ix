package com.admin.common.task;

import com.admin.common.dto.GostDto;
import com.admin.common.service.ForwardPortReservationService;
import com.admin.common.service.UserTunnelAliasService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import com.alibaba.fastjson2.JSONObject;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeCascadeCleanupTest {
    @Mock ForwardSyncTaskMapper tasks;
    @Mock NodeMapper nodes;
    @Mock ForwardService forwards;
    @Mock TunnelService tunnels;
    @Mock UserTunnelService grants;
    @Mock UserTunnelAliasService aliases;
    @Mock TunnelIngressNodeResolver ingress;
    @Mock ForwardSyncTaskExecutor executor;
    @Mock ForwardPortReservationService ports;
    ForwardSyncTaskService service;
    AutoCloseable mocks;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        service = new ForwardSyncTaskService();
        ReflectionTestUtils.setField(service, "forwardSyncTaskMapper", tasks);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", aliases);
        ReflectionTestUtils.setField(service, "ingressNodeResolver", ingress);
        ReflectionTestUtils.setField(service, "forwardSyncTaskExecutor", executor);
        ReflectionTestUtils.setField(service, "forwardPortReservationService", ports);
        ReflectionTestUtils.setField(service, "self", service);
    }
    @AfterEach void close() throws Exception { mocks.close(); }

    private ForwardSyncTask running(String endpoint) {
        ForwardSyncTask task = new ForwardSyncTask();
        task.setId(1L); task.setForwardId(20L); task.setNodeId(6L);
        task.setOperation("cleanup"); task.setEndpoint(endpoint);
        task.setServiceName("20_7_9"); task.setTaskStatus("running");
        when(tasks.selectById(1L)).thenReturn(task);
        when(nodes.selectById(6L)).thenReturn(new Node());
        return task;
    }
    private GostDto result(String msg) { GostDto r = new GostDto(); r.setMsg(msg); return r; }

    @Test void snapshotsIncludeAllIngressesEgressAndHistoricalNames() {
        Forward f = new Forward(); f.setId(20L); f.setUserId(7); f.setTunnelId(11);
        Tunnel t = new Tunnel(); t.setId(11L); t.setType(2); t.setInNodeId(5L); t.setOutNodeId(8L);
        UserTunnel grant = new UserTunnel(); grant.setId(9);
        when(grants.getOne(any(), eq(false))).thenReturn(grant);
        when(aliases.legacyNames(f, grant)).thenReturn(List.of("20_7_3"));
        when(ingress.resolveNodeIds(t)).thenReturn(Set.of(5L, 6L));
        when(tasks.selectList(any())).thenReturn(List.of());
        when(tasks.insert(any(ForwardSyncTask.class))).thenReturn(1);
        service.prepareCascadeDelete(f, t);
        var captor = ArgumentCaptor.forClass(ForwardSyncTask.class);
        verify(tasks, times(6)).insert(captor.capture());
        assertEquals(Set.of(5L, 6L, 8L), captor.getAllValues().stream().map(ForwardSyncTask::getNodeId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(captor.getAllValues().stream().allMatch(x -> "cleanup".equals(x.getOperation())));
        verify(tasks).cancelOperationsForForward(eq(20L), anyLong());
        verifyNoInteractions(ports);
    }

    @Test void missingForwardAndTunnelDoNotPreventIngressCleanup() {
        running("ingress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "udp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteChains(6L, "20_7_9")).thenReturn(result("chain 20_7_9_chains not found"));
            service.executeClaimedTask(1L);
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verify(ports).releaseAll(20L);
        }
    }

    @Test void offlineSurvivingNodeRetriesAndRetainsPorts() {
        running("egress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(6L, "20_7_9")).thenReturn(result("节点不在线"));
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), anyString(), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
        }
    }

    @Test void retiredOfflineNodeDoesNotRetryForever() {
        running("egress"); when(nodes.selectById(6L)).thenReturn(null);
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(6L, "20_7_9")).thenReturn(result("节点不在线"));
            service.executeClaimedTask(1L);
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verify(tasks, never()).markRetry(anyLong(), anyString(), anyLong(), anyLong());
        }
    }

    @Test void portsStayReservedUntilAllEndpointsHaveFinished() {
        when(tasks.selectCount(any())).thenReturn(1L);
        service.releaseCleanupReservations(20L);
        verifyNoInteractions(ports);
    }

    @Test void cancelledResumeThatFinishesAfterDeletionQueuesAnotherCleanup() {
        ForwardSyncTask task = running("ingress"); task.setOperation("resume");
        when(tasks.insert(any(ForwardSyncTask.class))).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "compensateCancelledCommand", task, new Forward());
        var captured = ArgumentCaptor.forClass(ForwardSyncTask.class);
        verify(tasks).insert(captured.capture());
        assertEquals("cleanup", captured.getValue().getOperation());
        assertEquals("20_7_9", captured.getValue().getServiceName());
        assertEquals(6L, captured.getValue().getNodeId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp"})
    void strictLegacyAgentRemovesTheSurvivingProtocolWithoutTouchingAnotherForward(String missing) {
        running("ingress");
        Set<String> live = new LinkedHashSet<>(List.of("20_7_9_tcp", "20_7_9_udp", "900_8_1_tcp"));
        live.remove("20_7_9_" + missing);
        Set<String> chains = new LinkedHashSet<>(List.of("20_7_9_chains", "900_8_1_chains"));
        try (var wire = legacyAgent(live, chains)) {
            // Reproduce why a pair request is not an absence confirmation.
            assertTrue(GostUtil.DeleteService(6L, "20_7_9").getMsg().contains("not found"));
            assertEquals(2, live.size());
            service.executeClaimedTask(1L);
            assertEquals(Set.of("900_8_1_tcp"), live);
            assertEquals(Set.of("900_8_1_chains"), chains);
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verify(ports).releaseAll(20L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"等待响应超时", "unknown command not found", "service unrelated_udp not found",
            "service 20_7_9_udp configuration not found", "not found"})
    void survivingNodeDoesNotAcceptAmbiguousProtocolErrorsAsSuccessfulCleanup(String error) {
        running("ingress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "udp")).thenReturn(result(error));
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), contains(error), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
            commands.verify(() -> GostUtil.DeleteChains(anyLong(), anyString()), never());
        }
    }

    @Test void lostAckRetriesTheAlreadyDeletedSideAndWaitsForTheChainBeforeReleasingPorts() {
        running("ingress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "tcp"))
                    .thenReturn(result("OK"), result("service 20_7_9_tcp not found"), result("service 20_7_9_tcp not found"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "udp"))
                    .thenReturn(result("等待响应超时"), result("service 20_7_9_udp not found"), result("service 20_7_9_udp not found"));
            commands.when(() -> GostUtil.DeleteChains(6L, "20_7_9"))
                    .thenReturn(result("chain unrelated_chains not found"), result("chain 20_7_9_chains not found"));
            service.executeClaimedTask(1L);
            service.executeClaimedTask(1L);
            verify(tasks, times(2)).markRetry(eq(1L), anyString(), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
            service.executeClaimedTask(1L);
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verify(ports).releaseAll(20L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown command not found", "service unrelated_tls not found", "节点不在线"})
    void egressNeedsItsOwnExactTargetAbsenceOrAck(String error) {
        running("egress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(6L, "20_7_9")).thenReturn(result(error));
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), contains(error), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp"})
    void ordinaryDeleteAlsoHandlesPartialLegacyPairsAndKeepsTheTailFlowReservation(String missing) {
        ordinaryDelete("ingress");
        Set<String> live = new LinkedHashSet<>(List.of("20_7_9_tcp", "20_7_9_udp", "900_8_1_tcp"));
        live.remove("20_7_9_" + missing);
        Set<String> chains = new LinkedHashSet<>(List.of("20_7_9_chains"));
        try (var wire = legacyAgent(live, chains)) {
            service.executeClaimedTask(1L);
            assertEquals(Set.of("900_8_1_tcp"), live);
            assertTrue(chains.isEmpty());
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verifyNoInteractions(ports);
            verify(forwards, never()).removeById(anyLong());
        }
    }

    @Test void ordinaryDeleteCannotFinishOnAChainFailureOrUnknownEgressCommand() {
        ordinaryDelete("ingress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", "udp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteChains(6L, "20_7_9")).thenReturn(result("chain another_chains not found"));
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), contains("chain another_chains not found"), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
        }
        clearInvocations(tasks);
        ordinaryDelete("egress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(6L, "20_7_9")).thenReturn(result("unknown command not found"));
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), contains("unknown command not found"), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verifyNoInteractions(ports);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp"})
    void ordinaryDeleteRetainsTheForwardAndPortsWhenEitherProtocolFails(String failedProtocol) {
        ordinaryDelete("ingress");
        try (var commands = mockStatic(GostUtil.class)) {
            for (String protocol : List.of("tcp", "udp")) {
                commands.when(() -> GostUtil.DeleteServiceProtocol(6L, "20_7_9", protocol))
                        .thenReturn(result(protocol.equals(failedProtocol) ? "等待响应超时" : "OK"));
            }
            service.executeClaimedTask(1L);
            verify(tasks).markRetry(eq(1L), contains("等待响应超时"), anyLong(), anyLong());
            verify(tasks, never()).markSucceeded(anyLong(), anyLong());
            verify(forwards, never()).removeById(anyLong());
            verifyNoInteractions(ports);
            commands.verify(() -> GostUtil.DeleteChains(anyLong(), anyString()), never());
        }
    }

    @Test void egressAcceptsOnlyItsOwnConfirmedAbsence() {
        running("egress");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(6L, "20_7_9"))
                    .thenReturn(result("service 20_7_9_tls not found"));
            service.executeClaimedTask(1L);
            verify(tasks).markSucceeded(eq(1L), anyLong());
            verify(ports).releaseAll(20L);
        }
    }

    private void ordinaryDelete(String endpoint) {
        ForwardSyncTask task = running(endpoint);
        task.setOperation("delete");
        task.setOperationId("delete-operation");
        Forward f = new Forward(); f.setId(20L); f.setUserId(7); f.setTunnelId(11); f.setStatus(3);
        Tunnel t = new Tunnel(); t.setId(11L); t.setType(2);
        UserTunnel grant = new UserTunnel(); grant.setId(9); grant.setUserId(7); grant.setTunnelId(11);
        when(forwards.getById(20L)).thenReturn(f);
        when(tunnels.getById(11)).thenReturn(t);
        when(grants.getOne(any(), eq(false))).thenReturn(grant);
        when(aliases.legacyNames(f, grant)).thenReturn(List.of());
        when(tasks.markSucceeded(eq(1L), anyLong())).thenReturn(1);
        when(tasks.selectByOperationId("delete-operation")).thenReturn(List.of());
    }

    private MockedStatic<WebSocketServer> legacyAgent(Set<String> live, Set<String> chains) {
        MockedStatic<WebSocketServer> wire = mockStatic(WebSocketServer.class);
        wire.when(() -> WebSocketServer.send_msg(eq(6L), any(), anyString())).thenAnswer(invocation -> {
            JSONObject data = invocation.getArgument(1);
            String command = invocation.getArgument(2);
            if ("DeleteService".equals(command)) {
                List<String> requested = data.getJSONArray("services").toJavaList(String.class);
                for (String name : requested) {
                    if (!live.contains(name)) return result("service " + name + " not found");
                }
                live.removeAll(requested);
                return result("OK");
            }
            if ("DeleteChains".equals(command)) {
                String name = data.getString("chain");
                return result(chains.remove(name) ? "OK" : "chain " + name + " not found");
            }
            return result("unknown command not found");
        });
        return wire;
    }
}

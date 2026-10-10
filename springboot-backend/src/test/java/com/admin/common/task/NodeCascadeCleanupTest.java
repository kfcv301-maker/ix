package com.admin.common.task;

import com.admin.common.dto.GostDto;
import com.admin.common.service.ForwardPortReservationService;
import com.admin.common.service.UserTunnelAliasService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeCascadeCleanupTest {
    @Mock ForwardSyncTaskMapper tasks;
    @Mock NodeMapper nodes;
    @Mock ForwardService forwards;
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
            commands.when(() -> GostUtil.DeleteService(6L, "20_7_9")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteChains(6L, "20_7_9")).thenReturn(result("not found"));
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
}

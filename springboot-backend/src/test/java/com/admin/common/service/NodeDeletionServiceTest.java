package com.admin.common.service;

import com.admin.common.task.ForwardSyncTaskService;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeDeletionServiceTest {
    @Mock NodeDeletionMapper deletionMapper;
    @Mock NodeMapper nodeMapper;
    @Mock TunnelMapper tunnelMapper;
    @Mock ForwardMapper forwardMapper;
    @Mock TunnelEntryNodeMapper entryNodeMapper;
    @Mock TunnelEntryDomainMapper entryDomainMapper;
    @Mock UserTunnelMapper grantMapper;
    @Mock SpeedLimitMapper speedLimitMapper;
    @Mock ForwardSyncTaskService syncTasks;
    @InjectMocks NodeDeletionService service;
    AutoCloseable mocks;
    MockedStatic<JwtUtil> identity;
    Node node;
    Tunnel tunnel;
    Forward forward;

    @BeforeEach void setup() {
        mocks = MockitoAnnotations.openMocks(this);
        identity = mockStatic(JwtUtil.class);
        identity.when(JwtUtil::getRoleIdFromToken).thenReturn(0);
        identity.when(JwtUtil::getUserIdFromToken).thenReturn(7);
        TransactionSynchronizationManager.initSynchronization();
        node = new Node(); node.setId(5L); node.setStatus(0); node.setOwnerUserId(7L);
        tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setInNodeId(5L); tunnel.setOwnerUserId(7L);
        forward = new Forward(); forward.setId(20L); forward.setTunnelId(11); forward.setStatus(3);
        when(deletionMapper.lockNode(5L)).thenReturn(node);
        when(deletionMapper.affectedTunnels(5L)).thenReturn(List.of(tunnel));
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward));
        when(forwardMapper.deleteById(20L)).thenReturn(1);
        when(tunnelMapper.deleteById(11L)).thenReturn(1);
        when(nodeMapper.deleteById(5L)).thenReturn(1);
        when(grantMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach void cleanup() throws Exception {
        TransactionSynchronizationManager.clearSynchronization();
        identity.close(); mocks.close();
    }

    @Test void offlineNodeAndAlreadyDeletingForwardAreRemovedInDependencyOrder() {
        assertEquals(0, service.deleteNode(5L).getCode());
        var order = inOrder(syncTasks, forwardMapper, grantMapper, entryNodeMapper, tunnelMapper, nodeMapper);
        order.verify(syncTasks).prepareCascadeDelete(forward, tunnel);
        order.verify(forwardMapper).deleteById(20L);
        order.verify(grantMapper).delete(any(QueryWrapper.class));
        order.verify(entryNodeMapper).delete(any(QueryWrapper.class));
        order.verify(tunnelMapper).deleteById(11L);
        order.verify(nodeMapper).deleteById(5L);
        verify(deletionMapper).deletePauseTasks(20L);
        verify(deletionMapper).deleteAliases(11L);
        verify(entryDomainMapper).delete(any(QueryWrapper.class));
        verify(speedLimitMapper).delete(any(QueryWrapper.class));
        verify(deletionMapper).deleteNodeReservations(5L);
        verify(syncTasks, never()).requestDelete(anyLong());
        verify(nodeMapper, never()).deleteById(6L);
        verify(tunnelMapper, never()).deleteById(12L);
    }

    @Test void onlineNodeUsesTheSameNonBlockingCascade() {
        node.setStatus(1);
        assertEquals(0, service.deleteNode(5L).getCode());
        verify(syncTasks).prepareCascadeDelete(forward, tunnel);
        verify(nodeMapper).deleteById(5L);
    }

    @Test void nodeWithoutTunnelsStillDeletes() {
        when(deletionMapper.affectedTunnels(5L)).thenReturn(List.of());
        assertEquals(0, service.deleteNode(5L).getCode());
        verifyNoInteractions(forwardMapper, tunnelMapper);
        verify(nodeMapper).deleteById(5L);
    }

    @Test void monitoringRetiresOnlyAfterCommitWhileCleanupCanKeepTheAgentConnection() {
        try (var monitoring = mockStatic(WebSocketServer.class)) {
            assertEquals(0, service.deleteNode(5L).getCode());
            monitoring.verify(() -> WebSocketServer.retireNodeMetrics(5L), never());
            verify(syncTasks, never()).closeRetiredNodeIfClean(5L);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            monitoring.verify(() -> WebSocketServer.retireNodeMetrics(5L));
            verify(syncTasks).closeRetiredNodeIfClean(5L);
        }
    }

    @Test void anotherUsersNodeCannotBeDeleted() {
        identity.when(JwtUtil::getRoleIdFromToken).thenReturn(1);
        node.setOwnerUserId(8L);
        assertEquals(403, service.deleteNode(5L).getCode());
        verifyNoInteractions(syncTasks, forwardMapper, nodeMapper);
    }

    @Test void owningOneEndpointDoesNotAuthorizeDeletingAnotherUsersTunnel() {
        identity.when(JwtUtil::getRoleIdFromToken).thenReturn(1);
        tunnel.setOwnerUserId(8L);
        assertEquals(403, service.deleteNode(5L).getCode());
        verifyNoInteractions(syncTasks, forwardMapper, nodeMapper);
    }

    @Test void failedDependencyDeleteThrowsSoTheTransactionRollsBack() {
        when(forwardMapper.deleteById(20L)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.deleteNode(5L));
        verifyNoInteractions(nodeMapper, tunnelMapper);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test void failedCleanupSnapshotAbortsBeforeAnyDataIsDeleted() {
        doThrow(new IllegalStateException("queue failed")).when(syncTasks).prepareCascadeDelete(forward, tunnel);
        assertThrows(IllegalStateException.class, () -> service.deleteNode(5L));
        verify(forwardMapper, never()).deleteById(anyLong());
        verifyNoInteractions(nodeMapper, tunnelMapper);
    }
}

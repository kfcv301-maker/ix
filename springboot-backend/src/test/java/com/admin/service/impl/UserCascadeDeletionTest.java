package com.admin.service.impl;

import com.admin.common.task.ForwardSyncTaskService;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeDeletionMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelAliasMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.StatisticsFlowService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCascadeDeletionTest {
    private final UserMapper users = mock(UserMapper.class);
    private final ForwardMapper forwards = mock(ForwardMapper.class);
    private final TunnelMapper tunnels = mock(TunnelMapper.class);
    private final NodeDeletionMapper cleanup = mock(NodeDeletionMapper.class);
    private final UserTunnelMapper grants = mock(UserTunnelMapper.class);
    private final UserTunnelAliasMapper aliases = mock(UserTunnelAliasMapper.class);
    private final StatisticsFlowService stats = mock(StatisticsFlowService.class);
    private final ForwardSyncTaskService sync = mock(ForwardSyncTaskService.class);
    private final UserServiceImpl service = new UserServiceImpl();

    UserCascadeDeletionTest() {
        ReflectionTestUtils.setField(service, "baseMapper", users);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "forwardMapper", forwards);
        ReflectionTestUtils.setField(service, "tunnelMapper", tunnels);
        ReflectionTestUtils.setField(service, "nodeDeletionMapper", cleanup);
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "userTunnelAliasMapper", aliases);
        ReflectionTestUtils.setField(service, "statisticsFlowService", stats);
        ReflectionTestUtils.setField(service, "forwardSyncTaskService", sync);
    }

    private User account(int role) {
        var user = new User();
        user.setId(7L);
        user.setRoleId(role);
        return user;
    }

    @Test void deletesEveryRuleAfterQueueingCleanupAndRevokesAfterCommit() {
        var tunnel = new Tunnel();
        tunnel.setId(9L);
        var first = new Forward();
        first.setId(20L);
        first.setTunnelId(9);
        var orphan = new Forward();
        orphan.setId(21L);
        orphan.setTunnelId(10);
        when(users.lockById(7L)).thenReturn(account(1));
        when(forwards.selectList(any())).thenReturn(List.of(first, orphan));
        when(tunnels.selectById(9)).thenReturn(tunnel);
        when(forwards.deleteById(20L)).thenReturn(1);
        when(forwards.deleteById(21L)).thenReturn(1);
        when(users.deleteById(7L)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var response = service.deleteUser(7L);
            assertEquals(0, response.getCode());
            assertTrue(response.getMsg().contains("2"));
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        } finally {
            TransactionSynchronizationManager.clear();
        }
        var order = inOrder(sync, forwards, cleanup, aliases, grants, stats, users);
        order.verify(users).lockById(7L);
        order.verify(forwards).selectList(any());
        order.verify(sync).prepareCascadeDelete(first, tunnel);
        order.verify(cleanup).deletePauseTasks(20L);
        order.verify(forwards).deleteById(20L);
        order.verify(sync).prepareCascadeDelete(orphan, null);
        order.verify(forwards).deleteById(21L);
        order.verify(aliases).deleteByUserId(7L);
        order.verify(grants).delete(any());
        order.verify(stats).remove(any());
        order.verify(users).deleteById(7L);
    }

    @Test void administratorCannotBeDeleted() {
        when(users.lockById(7L)).thenReturn(account(0));
        assertNotEquals(0, service.deleteUser(7L).getCode());
        verifyNoInteractions(forwards, sync, grants);
    }
}

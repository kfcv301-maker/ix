package com.admin.common.service;

import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeDeletionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardCreationServiceTest {
    @Test void aConcurrentTunnelDeletionPreventsAnOrphanedForwardInsert() {
        ForwardCreationService service = new ForwardCreationService();
        NodeDeletionMapper lifecycle = mock(NodeDeletionMapper.class);
        ForwardMapper forwards = mock(ForwardMapper.class);
        ReflectionTestUtils.setField(service, "nodeDeletionMapper", lifecycle);
        ReflectionTestUtils.setField(service, "forwardMapper", forwards);
        var users = mock(com.admin.mapper.UserMapper.class);
        ReflectionTestUtils.setField(service, "userMapper", users);
        User owner = new User(); owner.setStatus(1);
        when(users.lockById(7L)).thenReturn(owner);
        Forward forward = new Forward(); forward.setTunnelId(11); forward.setUserId(7);
        assertFalse(service.save(forward));
        verify(lifecycle).lockTunnel(11L);
        verifyNoInteractions(forwards);
        Tunnel tunnel = new Tunnel(); tunnel.setStatus(1);
        when(lifecycle.lockTunnel(11L)).thenReturn(tunnel);
        when(forwards.insert(forward)).thenReturn(1);
        assertTrue(service.save(forward));
    }

    @Test void disabledOwnerCannotCreateAndLateEditsQueuePause() {
        ForwardCreationService service = new ForwardCreationService();
        var users = mock(com.admin.mapper.UserMapper.class);
        var forwards = mock(ForwardMapper.class);
        var sync = mock(com.admin.common.task.ForwardSyncTaskService.class);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "forwardMapper", forwards);
        ReflectionTestUtils.setField(service, "syncTasks", sync);
        User owner = new User(); owner.setStatus(0);
        when(users.lockById(7L)).thenReturn(owner);
        Forward forward = new Forward(); forward.setId(20L); forward.setUserId(7); forward.setTunnelId(11);
        assertFalse(service.save(forward));
        verifyNoInteractions(forwards);
        when(forwards.updateById(forward)).thenReturn(1);
        when(sync.queueSafetyPause(20L)).thenReturn(com.admin.common.lang.R.ok());
        assertTrue(service.saveUpdated(forward));
        verify(sync).queueSafetyPause(20L);
        when(forwards.selectById(20L)).thenReturn(forward);
        service.enforceOwnerStatus(forward);
        verify(sync, times(2)).queueSafetyPause(20L);
    }
}

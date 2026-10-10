package com.admin.common.service;

import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
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
        Forward forward = new Forward(); forward.setTunnelId(11);
        assertFalse(service.save(forward));
        verify(lifecycle).lockTunnel(11L);
        verifyNoInteractions(forwards);
        Tunnel tunnel = new Tunnel(); tunnel.setStatus(1);
        when(lifecycle.lockTunnel(11L)).thenReturn(tunnel);
        when(forwards.insert(forward)).thenReturn(1);
        assertTrue(service.save(forward));
    }
}

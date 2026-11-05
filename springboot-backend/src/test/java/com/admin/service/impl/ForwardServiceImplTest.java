package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.UserTunnelService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardServiceImplTest {
    @Test void automaticPortsAvoidEphemeralRangeAndStillFindLastSafePort() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        for (int i = 0; i < 200; i++) {
            Integer port = ReflectionTestUtils.invokeMethod(service, "chooseRandomAvailablePort",
                    1000, 65535, java.util.Set.of(1000, 32767));
            assertNotNull(port); assertTrue((port > 1000 && port < 32767) || port >= 61000);
        }
        assertEquals(Integer.valueOf(32766), ReflectionTestUtils.invokeMethod(service,
                "chooseRandomAvailablePort", 32765, 60999, java.util.Set.of(32765, 32767)));
        assertNull(ReflectionTestUtils.invokeMethod(service, "chooseRandomAvailablePort",
                32768, 60999, java.util.Set.of()));
        assertNull(ReflectionTestUtils.invokeMethod(service, "chooseRandomAvailablePort",
                32767, 60999, java.util.Set.of(32767)));
        Integer highOnly = ReflectionTestUtils.invokeMethod(service, "chooseRandomAvailablePort",
                37769, 65535, java.util.Set.of());
        assertNotNull(highOnly); assertTrue(highOnly >= 61000);
    }
    @Test void changingTunnelStopsBeforeCreationWhenOldNodesCannotBeResolved() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        TunnelService tunnels = mock(TunnelService.class);
        UserTunnelService grants = mock(UserTunnelService.class);
        TunnelEntryNodeMapper entries = mock(TunnelEntryNodeMapper.class);
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        Forward old = new Forward(); old.setId(12L); old.setUserId(3); old.setTunnelId(42);
        Tunnel oldTunnel = new Tunnel(); oldTunnel.setId(42L); oldTunnel.setType(1);
        when(tunnels.getById(42)).thenReturn(oldTunnel);
        when(entries.selectList(any())).thenReturn(List.of());
        // Missing old nodes must fail before the new-node argument is used.
        R result = ReflectionTestUtils.invokeMethod(service, "updateGostServicesWithTunnelChange",
                old, new Forward(), new Tunnel(), null, null, null);
        assertNotNull(result);
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("停止切换隧道"));
    }
}

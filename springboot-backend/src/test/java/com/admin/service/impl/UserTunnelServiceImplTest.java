package com.admin.service.impl;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserTunnelServiceImplTest {

    @Test
    void privateTunnelCannotBeAssignedToAnotherUser() {
        TunnelService tunnelService = mock(TunnelService.class);
        UserTunnelServiceImpl service = new UserTunnelServiceImpl();
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);

        Tunnel tunnel = new Tunnel();
        tunnel.setOwnerUserId(7L);
        when(tunnelService.getById(42)).thenReturn(tunnel);

        UserTunnelDto assignment = new UserTunnelDto();
        assignment.setTunnelId(42);
        assignment.setUserId(8);
        R result = service.assignUserTunnel(assignment);

        assertEquals(403, result.getCode());
        assertEquals("用户自建隧道不能授权给其他用户", result.getMsg());
    }
}

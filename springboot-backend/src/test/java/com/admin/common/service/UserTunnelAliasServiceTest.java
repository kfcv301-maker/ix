package com.admin.common.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserTunnelAliasMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UserTunnelAliasServiceTest {
    @Test void cleanupStopsOnMissingAcknowledgmentAndRetriesOldNames() {
        UserTunnelAliasMapper mapper = mock(UserTunnelAliasMapper.class);
        when(mapper.selectAliases(9, 3, 42)).thenReturn(List.of(8));
        UserTunnelAliasService service = new UserTunnelAliasService();
        ReflectionTestUtils.setField(service, "aliasMapper", mapper);
        Forward forward = new Forward(); forward.setId(12L); forward.setUserId(3); forward.setTunnelId(42);
        UserTunnel grant = new UserTunnel(); grant.setId(9);
        Tunnel tunnel = new Tunnel(); tunnel.setType(2);
        GostDto offline = new GostDto(); offline.setMsg("node offline");
        GostDto ok = new GostDto(); ok.setMsg("OK");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteService(5L, "12_3_8")).thenReturn(offline);
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verify(() -> GostUtil.DeleteChains(5L, "12_3_8"), never());
            commands.when(() -> GostUtil.DeleteService(5L, "12_3_8")).thenReturn(ok);
            commands.when(() -> GostUtil.DeleteChains(5L, "12_3_8")).thenReturn(ok);
            assertNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verify(() -> GostUtil.DeleteChains(5L, "12_3_8"));
        }
    }
}

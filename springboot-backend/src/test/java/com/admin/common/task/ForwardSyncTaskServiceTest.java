package com.admin.common.task;

import com.admin.common.service.UserTunnelAliasService;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardSyncTask;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardSyncTaskServiceTest {
    @Test void aPreUpgradeTaskPausesBothItsOldNameAndTheCurrentCanonicalName() {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        UserTunnelService grants = mock(UserTunnelService.class);
        UserTunnelAliasService aliases = mock(UserTunnelAliasService.class);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", aliases);
        Forward forward = new Forward(); forward.setId(12L); forward.setUserId(3); forward.setTunnelId(42);
        UserTunnel grant = new UserTunnel(); grant.setId(9);
        when(grants.getOne(any(), eq(false))).thenReturn(grant);
        when(aliases.legacyNames(forward, grant)).thenReturn(List.of("12_3_8"));
        ForwardSyncTask task = new ForwardSyncTask(); task.setNodeId(5L); task.setOperation("pause"); task.setServiceName("12_3_8");
        GostDto ok = new GostDto(); ok.setMsg("OK");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.PauseService(5L, "12_3_8")).thenReturn(ok);
            commands.when(() -> GostUtil.PauseService(5L, "12_3_9")).thenReturn(ok);
            String error = ReflectionTestUtils.invokeMethod(service, "executeIngress", task, forward, new Tunnel());
            assertNull(error);
            commands.verify(() -> GostUtil.PauseService(5L, "12_3_8"));
            commands.verify(() -> GostUtil.PauseService(5L, "12_3_9"));
            assertEquals("12_3_8", task.getServiceName());
        }
    }
}

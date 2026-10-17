package com.admin.common.task;

import com.admin.common.service.UserTunnelAliasService;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.admin.entity.Forward;
import com.admin.entity.ForwardSyncTask;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardSyncTaskServiceTest {
    @Test void resumingOnALegacyAgentStopsBeforeLegacyCleanupOrResume() {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        UserTunnelAliasService aliases = mock(UserTunnelAliasService.class);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", aliases);
        Forward forward = new Forward(); forward.setProxyProtocol(2);
        ForwardSyncTask task = new ForwardSyncTask(); task.setNodeId(5L); task.setOperation("resume");
        GostDto denied = new GostDto(); denied.setMsg("请升级节点后端");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.CheckForwardProxyProtocolSupport(5L, 2)).thenReturn(denied);
            String error = ReflectionTestUtils.invokeMethod(service, "executeIngress", task, forward, new Tunnel());
            assertNotNull(error); assertTrue(error.contains("升级节点后端"));
            verifyNoInteractions(aliases);
            commands.verify(() -> GostUtil.ResumeService(anyLong(), anyString()), never());
            commands.verify(() -> GostUtil.UpdateChains(anyLong(), anyString(), anyString(), anyString(), any()), never());
        }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void resumingOnARevisionOneAgentStopsBeforeAnyCleanupResumeOrChainUpdate(int version) {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        UserTunnelAliasService aliases = mock(UserTunnelAliasService.class);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", aliases);
        Forward forward = new Forward(); forward.setProxyProtocol(version);
        ForwardSyncTask task = new ForwardSyncTask(); task.setNodeId(5L); task.setOperation("resume");
        GostDto revisionOne = new GostDto(); revisionOne.setMsg("OK");
        revisionOne.setData(JSONObject.of("forwardProxyProtocol", 1, "proxyProtocolVersions", JSONArray.of(1, 2)));
        try (var socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("ForwardProxyProtocolCapabilities")))
                    .thenReturn(revisionOne);
            String error = ReflectionTestUtils.invokeMethod(service, "executeIngress", task, forward, new Tunnel());
            assertNotNull(error); assertTrue(error.contains("升级节点后端"));
            verifyNoInteractions(aliases);
            socket.verify(() -> WebSocketServer.send_msg(anyLong(), any(),
                    argThat(command -> !"ForwardProxyProtocolCapabilities".equals(command))), never());
        }
    }

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

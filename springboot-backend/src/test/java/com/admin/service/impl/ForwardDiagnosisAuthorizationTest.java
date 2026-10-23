package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.TunnelForwardDiagnosisLimiter;
import com.admin.entity.*;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForwardDiagnosisAuthorizationTest {
    private ForwardServiceImpl service;
    private UserService users;
    private UserTunnelService grants;
    private TunnelService tunnels;
    private Forward forward;
    private User user;
    private UserTunnel grant;
    private Tunnel tunnel;
    private TunnelForwardDiagnosisLimiter limiter;
    private MockedStatic<JwtUtil> jwt;
    private MockedStatic<WebSocketServer> wire;

    @BeforeEach void setup() {
        service = spy(new ForwardServiceImpl());
        limiter = new TunnelForwardDiagnosisLimiter();
        ReflectionTestUtils.setField(service, "diagnosisLimiter", limiter);
        users = mock(UserService.class); grants = mock(UserTunnelService.class); tunnels = mock(TunnelService.class);
        NodeService nodes = mock(NodeService.class);
        TunnelEntryNodeMapper entries = mock(TunnelEntryNodeMapper.class);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "nodeService", nodes);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        forward = new Forward(); forward.setId(1L); forward.setUserId(7); forward.setTunnelId(11);
        forward.setRemoteAddr("first.example:443,second.example:443");
        user = new User(); user.setId(7L); user.setStatus(1); user.setRoleId(1);
        grant = new UserTunnel(); grant.setUserId(7); grant.setTunnelId(11); grant.setStatus(1);
        tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setType(1); tunnel.setStatus(1); tunnel.setInNodeId(5L);
        Node node = new Node(); node.setId(5L); node.setName("Shared Entry");
        doReturn(forward).when(service).getById(1L);
        when(users.getById(7)).thenReturn(user);
        when(grants.getOne(any())).thenReturn(grant);
        when(tunnels.getById(11)).thenReturn(tunnel);
        when(nodes.getNodeById(5L)).thenReturn(node);
        when(entries.selectList(any())).thenReturn(List.of());
        jwt = mockStatic(JwtUtil.class);
        jwt.when(JwtUtil::getUserIdFromToken).thenReturn(7);
        jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(1);
        jwt.when(JwtUtil::getNameFromToken).thenReturn("owner");
        wire = mockStatic(WebSocketServer.class);
        GostDto failure = new GostDto(); failure.setMsg("offline fixture");
        wire.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing"))).thenReturn(failure);
    }
    @AfterEach void close() { wire.close(); jwt.close(); }

    @Test void anExpiredSharedGrantCannotProbeEvenWhileItsForwardStillExists() {
        grant.setExpTime(1L);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        wire.verifyNoInteractions();
    }

    @Test void missingOrDisabledGrantCannotProbe() {
        grant.setStatus(0);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        when(grants.getOne(any())).thenReturn(null);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        wire.verifyNoInteractions();
    }

    @Test void expiredAccountAndPrivateTunnelTransferCannotProbe() {
        user.setExpTime(1L);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        user.setExpTime(null); tunnel.setOwnerUserId(8L);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        tunnel.setOwnerUserId(null); tunnel.setStatus(0);
        assertEquals(403, service.diagnoseForward(1L).getCode());
        wire.verifyNoInteractions();
    }

    @Test void grantRevokedDuringFirstProbeStopsRemainingTargets() {
        wire.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing"))).thenAnswer(call -> {
            grant.setStatus(0);
            GostDto failure = new GostDto(); failure.setMsg("offline fixture"); return failure;
        });
        assertEquals(403, service.diagnoseForward(1L).getCode());
        wire.verify(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing")), times(1));
    }

    @Test void validSharedGrantStillAllowsBothTargetsWithoutReturningInfrastructure() {
        var response = service.diagnoseForward(1L);
        assertEquals(0, response.getCode());
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(response).contains("Shared Entry"));
        wire.verify(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing")), times(2));
    }

    @Test void forwardTransferredDuringFirstProbeCannotContinueUnderTheOldOwner() {
        wire.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing"))).thenAnswer(call -> {
            forward.setUserId(8);
            GostDto failure = new GostDto(); failure.setMsg("offline fixture"); return failure;
        });
        assertEquals(403, service.diagnoseForward(1L).getCode());
        wire.verify(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing")), times(1));
    }

    @Test void administratorDoesNotNeedAnAssignmentButCannotContinueAfterDemotion() {
        user.setRoleId(0); jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(0);
        when(grants.getOne(any())).thenReturn(null);
        assertEquals(0, service.diagnoseForward(1L).getCode());
        wire.verify(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing")), times(2));
        user.setRoleId(1);
        assertEquals(403, service.diagnoseForward(1L).getCode());
    }

    @Test void duplicateSingleAndGlobalBatchCapacityRejectWithoutSendingCommands() {
        try (var duplicate = limiter.acquireForward(7, 1L)) {
            assertEquals(429, service.diagnoseForward(1L).getCode());
        }
        try (var first = limiter.acquire(8, 12L); var second = limiter.acquire(9, 13L)) {
            assertEquals(429, service.diagnoseForward(1L).getCode());
        }
        wire.verifyNoInteractions();
        assertEquals(0, service.diagnoseForward(1L).getCode());
    }

    @Test void revokedPermissionAndExceptionsReleaseSingleProbeCapacity() {
        wire.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing"))).thenAnswer(call -> {
            grant.setStatus(0);
            GostDto result = new GostDto(); result.setMsg("offline fixture"); return result;
        });
        assertEquals(403, service.diagnoseForward(1L).getCode());
        try (var released = limiter.acquireForward(7, 1L)) { assertNotNull(released); }
        grant.setStatus(1);
        when(tunnels.getById(11)).thenReturn(tunnel).thenThrow(new IllegalStateException("storage fixture failure"));
        assertThrows(IllegalStateException.class, () -> service.diagnoseForward(1L));
        try (var released = limiter.acquireForward(7, 1L)) { assertNotNull(released); }
    }

    @Test void interruptionReleasesSingleProbeCapacityWithoutSendingCommands() {
        try {
            Thread.currentThread().interrupt();
            assertNotEquals(0, service.diagnoseForward(1L).getCode());
        } finally { Thread.interrupted(); }
        wire.verifyNoInteractions();
        try (var released = limiter.acquireForward(7, 1L)) { assertNotNull(released); }
    }
}

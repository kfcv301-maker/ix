package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForwardDiagnosisCancellationTest {
    @Test void cancellingDuringFirstNodeCommandPreventsLaterTargetsEvenIfTransportSwallowsInterrupts() {
        ForwardServiceImpl service = spy(new ForwardServiceImpl());
        TunnelService tunnels = mock(TunnelService.class);
        NodeService nodes = mock(NodeService.class);
        TunnelEntryNodeMapper entries = mock(TunnelEntryNodeMapper.class);
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "nodeService", nodes);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        Forward forward = new Forward(); forward.setId(1L); forward.setUserId(7); forward.setTunnelId(11);
        forward.setRemoteAddr("first.example:443,second.example:443");
        doReturn(forward).when(service).getById(1L);
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setType(1); tunnel.setInNodeId(5L);
        Node node = new Node(); node.setId(5L); node.setName("Entry");
        when(tunnels.getById(11)).thenReturn(tunnel);
        when(nodes.getNodeById(5L)).thenReturn(node);
        when(entries.selectList(any())).thenReturn(List.of());
        AtomicBoolean continuing = new AtomicBoolean(true);
        try (var commands = mockStatic(WebSocketServer.class)) {
            commands.when(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing"))).thenAnswer(call -> {
                continuing.set(false);
                GostDto result = new GostDto(); result.setMsg("发送消息失败"); return result;
            });
            R result = service.diagnoseForwardForSystem(forward, continuing::get);
            assertNotEquals(0, result.getCode());
            assertEquals("检测已中止", result.getMsg());
            commands.verify(() -> WebSocketServer.send_msg(eq(5L), any(), eq("TcpPing")), times(1));
        }
    }

    @Test void changedOwnershipNeverReachesANodeCommand() {
        ForwardServiceImpl service = spy(new ForwardServiceImpl());
        Forward selected = new Forward(); selected.setId(1L); selected.setUserId(7); selected.setTunnelId(11);
        Forward latest = new Forward(); latest.setId(1L); latest.setUserId(8); latest.setTunnelId(11);
        doReturn(latest).when(service).getById(1L);
        try (var commands = mockStatic(WebSocketServer.class)) {
            assertNotEquals(0, service.diagnoseForwardForSystem(selected, () -> true).getCode());
            commands.verifyNoInteractions();
        }
    }
}

package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.service.NodeAgentConfigService;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.service.TunnelService;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeUpdateSettingsTest {
    private final NodeServiceImpl service = spy(new NodeServiceImpl());
    private final TunnelService tunnels = mock(TunnelService.class);
    private final NodeAgentConfigService configs = mock(NodeAgentConfigService.class);
    private Node node;
    private NodeUpdateDto update;
    private MockedStatic<JwtUtil> identity;
    private MockedStatic<WebSocketServer> wire;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "agentConfigService", configs);
        node = new Node(); node.setId(11L); node.setStatus(1);
        node.setHttp(0); node.setTls(1); node.setSocks(1);
        doReturn(node).when(service).getById(11L);
        doReturn(true).when(service).updateById(any(Node.class));
        when(tunnels.list(any(QueryWrapper.class))).thenReturn(List.of());
        update = new NodeUpdateDto(); update.setId(11L); update.setName("Node");
        update.setIp("entry.example.com"); update.setServerIp("192.0.2.1");
        update.setPortSta(10000); update.setPortEnd(20000); update.setHttp(1);
        identity = mockStatic(JwtUtil.class); identity.when(JwtUtil::getRoleIdFromToken).thenReturn(0);
        wire = mockStatic(WebSocketServer.class);
        GostDto ok = new GostDto(); ok.setMsg("OK");
        wire.when(() -> WebSocketServer.send_msg(eq(11L), any(JSONObject.class), eq("SetProtocol"))).thenReturn(ok);
    }

    @AfterEach void close() { wire.close(); identity.close(); }

    @Test void invalidDdnsNeverChangesProtocolsOrPersistsAnyPartOfTheRequest() {
        update.setDdnsEnabled(true); update.setCfRecordName("not a domain"); update.setCfApiToken("test-token");
        assertNotEquals(0, service.updateNode(update).getCode());
        wire.verifyNoInteractions();
        verify(service, never()).updateById(any(Node.class));
        verifyNoInteractions(tunnels, configs);
    }

    @Test void invalidTcpAndPortSettingsNeverReachTheLiveNode() {
        update.setTcpTuningProfile("invalid");
        assertNotEquals(0, service.updateNode(update).getCode());
        update.setTcpTuningProfile("balanced"); update.setPortSta(20001);
        assertThrows(RuntimeException.class, () -> service.updateNode(update));
        wire.verifyNoInteractions();
        verify(service, never()).updateById(any(Node.class));
        verifyNoInteractions(tunnels, configs);
    }

    @Test void partialProtocolEditKeepsTheOtherLiveProtocolSwitches() {
        assertEquals(0, service.updateNode(update).getCode());
        wire.verify(() -> WebSocketServer.send_msg(eq(11L), argThat(payload -> {
            JSONObject flags = (JSONObject) payload;
            return flags.getIntValue("http") == 1 && flags.getIntValue("tls") == 1 && flags.getIntValue("socks") == 1;
        }), eq("SetProtocol")));
        verify(configs).requestSync(11L);
    }

    @Test void settingsOnlyEditNeverReappliesTheNodeProtocolPolicy() {
        update.setHttp(null); update.setTcpTuningProfile("small");
        assertEquals(0, service.updateNode(update).getCode());
        wire.verifyNoInteractions();
        verify(configs).requestSync(11L);
    }

    @Test void invalidFlagsAreRejectedEvenForOfflineNodes() {
        node.setStatus(0); update.setTls(2);
        assertNotEquals(0, service.updateNode(update).getCode());
        wire.verifyNoInteractions();
        verify(service, never()).updateById(any(Node.class));
        verifyNoInteractions(tunnels, configs);
    }

    @Test void aFailedNodeSaveCannotStillRewriteTunnelAddressesOrPushRuntimeConfig() {
        update.setHttp(null);
        doReturn(false).when(service).updateById(any(Node.class));
        assertNotEquals(0, service.updateNode(update).getCode());
        wire.verifyNoInteractions();
        verifyNoInteractions(tunnels, configs);
    }

    @Test void missingProtocolAcknowledgementCannotSaveTheRequest() {
        wire.when(() -> WebSocketServer.send_msg(eq(11L), any(JSONObject.class), eq("SetProtocol"))).thenReturn(null);
        assertNotEquals(0, service.updateNode(update).getCode());
        verify(service, never()).updateById(any(Node.class));
        verifyNoInteractions(tunnels, configs);
    }
}

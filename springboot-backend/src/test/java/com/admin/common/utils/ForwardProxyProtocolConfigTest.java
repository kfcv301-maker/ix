package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForwardProxyProtocolConfigTest {
    private GostDto reply(Object data) {
        GostDto result = new GostDto(); result.setMsg("OK"); result.setData(data); return result;
    }

    private void assertSendingOnlyOnIngressTcp(JSONArray services, int version, int tunnelType) {
        assertEquals(2, services.size());
        JSONObject tcp = services.getJSONObject(0);
        JSONObject udp = services.getJSONObject(1);
        assertEquals("tcp", tcp.getJSONObject("handler").getString("type"));
        assertEquals(version, tcp.getJSONObject("handler").getJSONObject("metadata").getIntValue("proxyProtocol"));
        assertNull(udp.getJSONObject("handler").get("metadata"));
        assertNull(tcp.getJSONObject("listener").get("metadata"));
        assertEquals(true, udp.getJSONObject("listener").getJSONObject("metadata").getBoolean("keepAlive"));
        if (tunnelType == 2) assertEquals("rule_chains", tcp.getJSONObject("handler").getString("chain"));
        else assertEquals("127.0.0.1:8443", tcp.getJSONObject("forwarder").getJSONArray("nodes").getJSONObject(0).getString("addr"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void createAndUpdatePreserveProxyVersionForOrdinaryAndTunnelForwarding(int version) {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("ForwardProxyProtocolCapabilities")))
                    .thenReturn(reply(JSONObject.of("forwardProxyProtocol", 2, "proxyProtocolVersions", JSONArray.of(1, 2))));
            for (int type : new int[]{1, 2}) {
                socket.when(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), anyString()))
                        .thenAnswer(call -> { assertSendingOnlyOnIngressTcp(call.getArgument(1), version, type); return reply(null); });
                assertEquals("OK", GostUtil.AddService(1L, "rule", 18100, null, "127.0.0.1:8443", type,
                        new Tunnel(), "fifo", null, null, version).getMsg());
                assertEquals("OK", GostUtil.UpdateService(1L, "rule", 18100, null, "127.0.0.1:8443", type,
                        new Tunnel(), "fifo", null, null, version).getMsg());
            }
        }
    }

    @Test void oldAgentAndUnconfirmedVersionsCannotSilentlyEnableSending() {
        Object[] responses = {null, JSONObject.of("forwardProxyProtocol", 2),
                JSONObject.of("forwardProxyProtocol", 2, "proxyProtocolVersions", JSONArray.of(1)),
                JSONObject.of("forwardProxyProtocol", 1, "proxyProtocolVersions", JSONArray.of(1, 2)),
                JSONObject.of("forwardProxyProtocol", 0, "proxyProtocolVersions", JSONArray.of(1, 2)),
                JSONObject.of("forwardProxyProtocol", "bad", "proxyProtocolVersions", "bad")};
        for (Object response : responses) {
            try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
                socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("ForwardProxyProtocolCapabilities")))
                        .thenReturn(reply(response));
                assertTrue(GostUtil.UpdateService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                        new Tunnel(), "fifo", null, null, 2).getMsg().contains("升级节点后端"));
                socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("UpdateService")), never());
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void revisionOneAgentCannotCreateUpdateOrRestoreTcpEvenWhenItAdvertisesBothVersions(int version) {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("ForwardProxyProtocolCapabilities")))
                    .thenReturn(reply(JSONObject.of("forwardProxyProtocol", 1, "proxyProtocolVersions", JSONArray.of(1, 2))));
            assertTrue(GostUtil.AddService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                    new Tunnel(), "fifo", null, null, version).getMsg().contains("升级节点后端"));
            assertTrue(GostUtil.UpdateService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                    new Tunnel(), "fifo", null, null, version).getMsg().contains("升级节点后端"));
            assertTrue(GostUtil.AddMissingService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                    new Tunnel(), "fifo", null, null, true, false, version).getMsg().contains("升级节点后端"));
            socket.verify(() -> WebSocketServer.send_msg(anyLong(), any(),
                    argThat(command -> !"ForwardProxyProtocolCapabilities".equals(command))), never());
        }
    }

    @Test void restoringOnlyUdpDoesNotRequireOrApplyTcpProxyProtocol() {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), eq("AddService")))
                    .thenAnswer(call -> {
                        JSONArray services = call.getArgument(1);
                        assertEquals(1, services.size());
                        assertEquals("rule_udp", services.getJSONObject(0).getString("name"));
                        assertNull(services.getJSONObject(0).getJSONObject("handler").get("metadata"));
                        return reply(null);
                    });
            assertEquals("OK", GostUtil.AddMissingService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                    new Tunnel(), "fifo", null, null, false, true, 2).getMsg());
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("ForwardProxyProtocolCapabilities")), never());
        }
    }

    @Test void disabledAndLegacySettingsNeverProbeOrAddHeaders() {
        for (Integer version : new Integer[]{null, 0}) {
            try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
                socket.when(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), eq("UpdateService")))
                        .thenAnswer(call -> {
                            JSONArray services = call.getArgument(1);
                            for (Object item : services) assertNull(((JSONObject) item).getJSONObject("handler").get("metadata"));
                            return reply(null);
                        });
                assertEquals("OK", GostUtil.UpdateService(1L, "rule", 18100, null, "127.0.0.1:8443", 1,
                        new Tunnel(), "fifo", null, null, version).getMsg());
                socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("ForwardProxyProtocolCapabilities")), never());
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 3, 65535})
    void invalidInternalVersionsAreRejectedBeforeAnyNodeCommand(int version) {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            assertThrows(IllegalArgumentException.class, () -> GostUtil.AddService(1L, "rule", 18100, null,
                    "127.0.0.1:8443", 1, new Tunnel(), "fifo", null, null, version));
            socket.verifyNoInteractions();
        }
    }

    @Test void remoteRelayServiceDoesNotSendASecondHeader() {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(2L), any(JSONArray.class), eq("AddService")))
                    .thenAnswer(call -> {
                        JSONArray services = call.getArgument(1);
                        JSONObject service = services.getJSONObject(0);
                        assertEquals("relay", service.getJSONObject("handler").getString("type"));
                        assertNull(service.getJSONObject("handler").get("metadata"));
                        assertNull(service.getJSONObject("listener").get("metadata"));
                        return reply(null);
                    });
            assertEquals("OK", GostUtil.AddRemoteService(2L, "rule", 18101, "127.0.0.1:8443", "mtls", "fifo", null).getMsg());
        }
    }
}

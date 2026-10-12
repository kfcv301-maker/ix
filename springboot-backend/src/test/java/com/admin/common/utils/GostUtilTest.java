package com.admin.common.utils;

import com.admin.entity.Tunnel;
import com.admin.common.dto.GostDto;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GostUtilTest {
    @Test void missingProtocolUsesCreateCommandAndCarriesItsPolicy() {
        JSONObject policy = new JSONObject();
        policy.put("tls", "TLS_ONLY");
        policy.put("allowUdp", false);
        policy.put("revision", 7L);
        GostDto supported = new GostDto();
        supported.setMsg("OK");
        supported.setData(JSONObject.of("userProtocolPolicy", 1));
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("UserProtocolPolicyCapabilities")))
                    .thenReturn(supported);
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), eq("AddService")))
                    .thenAnswer(invocation -> {
                        JSONArray services = invocation.getArgument(1);
                        assertEquals(1, services.size());
                        JSONObject service = services.getJSONObject(0);
                        assertEquals("12_7_9_udp", service.getString("name"));
                        assertEquals(policy, service.getJSONObject("metadata").getJSONObject("userProtocolPolicy"));
                        return supported;
                    });
            assertEquals("OK", GostUtil.AddMissingService(1L, "12_7_9", 18103, 8,
                    "127.0.0.1:443", 1, new Tunnel(), "fifo", null, policy, false, true).getMsg());
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("UpdateService")), never());
        }
    }

    @Test void missingProtocolStillRequiresPolicyCapableAgent() {
        JSONObject policy = JSONObject.of("tls", "TLS_DENY", "allowUdp", true);
        GostDto unknown = new GostDto(); unknown.setMsg("未知命令类型");
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("UserProtocolPolicyCapabilities"))).thenReturn(unknown);
            assertTrue(GostUtil.AddMissingService(1L, "12_7_9", 18103, null,
                    "127.0.0.1:443", 1, new Tunnel(), "fifo", null, policy, true, false)
                    .getMsg().contains("升级节点后端"));
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("AddService")), never());
        }
    }

    @Test void completeInventoryDoesNotIssueACommand() {
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            assertEquals("OK", GostUtil.AddMissingService(1L, "12_7_9", 18103, null,
                    "127.0.0.1:443", 1, new Tunnel(), "fifo", null, null, false, false).getMsg());
            socket.verifyNoInteractions();
        }
    }

    @org.junit.jupiter.api.Test
    void oldAgentCannotSilentlyAcceptARestrictedForward() {
        var policy = new JSONObject(); policy.put("tls", "TLS_ONLY"); policy.put("allowUdp", false);
        GostDto unknown = new GostDto(); unknown.setMsg("未知命令类型");
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(1L), any(), eq("UserProtocolPolicyCapabilities"))).thenReturn(unknown);
            var result = GostUtil.AddService(1L, "12_7_9", 18103, null, "127.0.0.1:443", 1, new Tunnel(), "fifo", null, policy);
            org.junit.jupiter.api.Assertions.assertTrue(result.getMsg().contains("升级节点后端"));
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(), eq("AddService")), never());
        }
    }
    @Test void createAndUpdateSendValidIpv6TcpAndUdpEndpoints() {
        String[][] addresses = {
                {"::1", "[::1]:18103"},
                {"[::1]", "[::1]:18103"},
                {"2405:84c0:802e:b7::", "[2405:84c0:802e:b7::]:18103"},
                {"0.0.0.0", "0.0.0.0:18103"}
        };
        try (MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class)) {
            for (String[] address : addresses) {
                Tunnel tunnel = new Tunnel();
                tunnel.setTcpListenAddr(address[0]);
                tunnel.setUdpListenAddr(address[0]);
                socket.when(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), anyString()))
                        .thenAnswer(invocation -> {
                            JSONArray services = invocation.getArgument(1);
                            assertEquals(2, services.size());
                            assertEquals(address[1], services.getJSONObject(0).getString("addr"));
                            assertEquals(address[1], services.getJSONObject(1).getString("addr"));
                            return null;
                        });
                GostUtil.AddService(1L, "test", 18103, null, "[::1]:18581", 1, tunnel, "fifo", null);
                GostUtil.UpdateService(1L, "test", 18103, null, "[::1]:18581", 1, tunnel, "fifo", null);
                assertEquals(address[1], GostUtil.formatAddress(address[0], 18103));
            }
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), eq("AddService")), times(4));
            socket.verify(() -> WebSocketServer.send_msg(eq(1L), any(JSONArray.class), eq("UpdateService")), times(4));
        }
    }
}

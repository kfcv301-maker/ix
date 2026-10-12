package com.admin.common.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserTunnelAliasMapper;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class UserTunnelAliasServiceTest {
    private final UserTunnelAliasMapper mapper = mock(UserTunnelAliasMapper.class);
    private final UserTunnelAliasService service = new UserTunnelAliasService();
    private final Forward forward = new Forward();
    private final UserTunnel grant = new UserTunnel();
    private final Tunnel tunnel = new Tunnel();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "aliasMapper", mapper);
        when(mapper.selectAliases(9, 3, 42)).thenReturn(List.of(8));
        forward.setId(12L); forward.setUserId(3); forward.setTunnelId(42);
        grant.setId(9); grant.setUserId(3); grant.setTunnelId(42);
        tunnel.setId(42L); tunnel.setType(2);
    }

    private static GostDto result(String message) {
        if (message == null) return null;
        GostDto result = new GostDto(); result.setMsg(message); return result;
    }

    @Test void cleanupStopsOnMissingAcknowledgmentAndRetriesOldNames() {
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "tcp")).thenReturn(result("node offline"));
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verify(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "udp"), never());
            commands.verify(() -> GostUtil.DeleteChains(5L, "12_3_8"), never());
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "udp")).thenReturn(result("service 12_3_8_udp not found"));
            commands.when(() -> GostUtil.DeleteChains(5L, "12_3_8")).thenReturn(result("OK"));
            assertNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verify(() -> GostUtil.DeleteChains(5L, "12_3_8"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp", "both", "neither"})
    void singletonDeletesCleanPartialLegacyInventoryOnOldAgent(String existing) {
        Set<String> running = new HashSet<>(List.of("12_3_9_tcp", "12_3_9_udp", "12_4_8_tcp", "13_3_8_udp"));
        if ("tcp".equals(existing) || "both".equals(existing)) running.add("12_3_8_tcp");
        if ("udp".equals(existing) || "both".equals(existing)) running.add("12_3_8_udp");
        Set<String> protectedServices = Set.of("12_3_9_tcp", "12_3_9_udp", "12_4_8_tcp", "13_3_8_udp");
        List<String> requested = new ArrayList<>();
        try (var socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(5L), any(JSONObject.class), eq("DeleteService")))
                    .thenAnswer(call -> {
                        JSONObject data = call.getArgument(1);
                        List<String> names = data.getList("services", String.class);
                        assertEquals(1, names.size());
                        requested.addAll(names);
                        // Old Agent preflights the complete batch and deletes
                        // nothing whenever any requested service is absent.
                        for (String name : names) {
                            if (!running.contains(name)) return result("service " + name + " not found");
                        }
                        running.removeAll(names);
                        return result("OK");
                    });
            socket.when(() -> WebSocketServer.send_msg(eq(5L), any(JSONObject.class), eq("DeleteChains")))
                    .thenAnswer(call -> {
                        JSONObject data = call.getArgument(1);
                        assertEquals("12_3_8_chains", data.getString("chain"));
                        return result("chain 12_3_8_chains not found");
                    });
            assertNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
        }
        assertEquals(List.of("12_3_8_tcp", "12_3_8_udp"), requested);
        assertEquals(protectedServices, running);
        verify(mapper).selectAliases(9, 3, 42);
        verifyNoMoreInteractions(mapper);
    }

    private static Stream<Arguments> unconfirmedDeletes() {
        List<String> messages = Arrays.asList(null, "请求超时", "未知命令类型", "command not found",
                "service 99_3_8_udp not found", "service 12_3_8_udp not found: timeout", "unsupported command");
        return Stream.of("tcp", "udp").flatMap(protocol -> messages.stream().map(message -> Arguments.of(protocol, message)));
    }

    @ParameterizedTest
    @MethodSource("unconfirmedDeletes")
    void neitherProtocolMistakesOtherErrorsForAbsence(String failedProtocol, String message) {
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "udp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", failedProtocol)).thenReturn(result(message));
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verify(() -> GostUtil.DeleteChains(5L, "12_3_8"), never());
            if ("tcp".equals(failedProtocol)) {
                commands.verify(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "udp"), never());
            }
        }
    }

    @Test void remoteAbsenceMustIdentifyTheExactRemoteService() {
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteRemoteService(5L, "12_3_8")).thenReturn(result("command not found"));
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, false));
            commands.when(() -> GostUtil.DeleteRemoteService(5L, "12_3_8")).thenReturn(result("service 12_4_8_tls not found"));
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, false));
            commands.when(() -> GostUtil.DeleteRemoteService(5L, "12_3_8")).thenReturn(result("service 12_3_8_tls not found"));
            assertNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, false));
            commands.verify(() -> GostUtil.DeleteServiceProtocol(anyLong(), anyString(), anyString()), never());
            commands.verify(() -> GostUtil.DeleteChains(anyLong(), anyString()), never());
        }
    }

    @Test void chainAbsenceMustIdentifyTheExactChain() {
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "tcp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteServiceProtocol(5L, "12_3_8", "udp")).thenReturn(result("OK"));
            commands.when(() -> GostUtil.DeleteChains(5L, "12_3_8")).thenReturn(result("chain 13_3_8_chains not found"));
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.when(() -> GostUtil.DeleteChains(5L, "12_3_8")).thenReturn(result("chain 12_3_8_chains not found"));
            assertNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
        }
    }

    @Test void canonicalAndInvalidAliasIdsAreNeverTreatedAsLegacyServices() {
        when(mapper.selectAliases(9, 3, 42)).thenReturn(Arrays.asList(8, 9, 8, null, 0, -1));
        assertEquals(List.of("12_3_8"), service.legacyNames(forward, grant));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "tunnel", "unknownOwner", "unknownTunnel", "missingGrantId"})
    void mismatchedGrantIdentityCannotDeleteAnyService(String mismatch) {
        switch (mismatch) {
            case "owner" -> grant.setUserId(4);
            case "tunnel" -> grant.setTunnelId(43);
            case "unknownOwner" -> grant.setUserId(null);
            case "unknownTunnel" -> grant.setTunnelId(null);
            case "missingGrantId" -> grant.setId(null);
            default -> fail("Unknown case");
        }
        try (var commands = mockStatic(GostUtil.class)) {
            assertTrue(service.legacyNames(forward, grant).isEmpty());
            assertNotNull(service.removeLegacyOnNode(5L, forward, tunnel, grant, true));
            commands.verifyNoInteractions();
        }
        verifyNoInteractions(mapper);
    }
}

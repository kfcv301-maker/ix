package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForwardProxyProtocolServiceTest {
    private Forward existing() {
        Forward row = new Forward(); row.setId(8L); row.setUserId(7); row.setUserName("owner");
        row.setTunnelId(9); row.setInPort(18100); row.setOutPort(18200); row.setProxyProtocol(2);
        return row;
    }
    private ForwardUpdateDto edit() {
        ForwardUpdateDto update = new ForwardUpdateDto(); update.setId(8L); update.setUserId(7);
        update.setName("edited"); update.setTunnelId(9); update.setRemoteAddr("127.0.0.1:443"); return update;
    }

    @Test void anOlderClientOmittingTheSettingPreservesTheStoredVersion() {
        Forward result = ReflectionTestUtils.invokeMethod(new ForwardServiceImpl(), "updateForwardEntity",
                edit(), existing(), new Tunnel());
        assertEquals(2, result.getProxyProtocol());
        Forward legacy = existing(); legacy.setProxyProtocol(null);
        result = ReflectionTestUtils.invokeMethod(new ForwardServiceImpl(), "updateForwardEntity", edit(), legacy, new Tunnel());
        assertEquals(0, result.getProxyProtocol());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void anExplicitEditCanDisableOrSelectEitherVersion(int version) {
        ForwardUpdateDto update = edit(); update.setProxyProtocol(version);
        Forward result = ReflectionTestUtils.invokeMethod(new ForwardServiceImpl(), "updateForwardEntity", update,
                existing(), new Tunnel());
        assertEquals(version, result.getProxyProtocol());
        assertEquals(7, result.getUserId());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 3})
    void serviceAndHttpValidationBothRejectInvalidVersionsBeforeLookingUpNodes(int version) {
        ForwardDto create = new ForwardDto(); create.setProxyProtocol(version);
        ForwardUpdateDto update = edit(); update.setProxyProtocol(version);
        ForwardServiceImpl service = new ForwardServiceImpl();
        assertTrue(service.createForward(create).getMsg().contains("Proxy Protocol"));
        assertTrue(service.updateForward(update).getMsg().contains("Proxy Protocol"));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(create).stream()
                    .anyMatch(error -> error.getPropertyPath().toString().equals("proxyProtocol")));
            assertTrue(factory.getValidator().validate(update).stream()
                    .anyMatch(error -> error.getPropertyPath().toString().equals("proxyProtocol")));
        }
    }

    private Object nodes() throws ReflectiveOperationException {
        Class<?> type = Class.forName(ForwardServiceImpl.class.getName() + "$NodeInfo");
        var constructor = type.getDeclaredConstructor(boolean.class, String.class, List.class, Node.class);
        constructor.setAccessible(true);
        Node first = new Node(); first.setId(11L); first.setName("first");
        Node second = new Node(); second.setId(12L); second.setName("old-second");
        return constructor.newInstance(false, null, List.of(first, second), null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void multiIngressCapabilityFailureStopsCreateUpdateAndTunnelChangeBeforeAnyMutation(boolean revisionOne) throws Exception {
        Forward forward = existing();
        Object nodeInfo = nodes();
        try (var socket = mockStatic(WebSocketServer.class)) {
            socket.when(() -> WebSocketServer.send_msg(eq(11L), any(), eq("ForwardProxyProtocolCapabilities")))
                    .thenAnswer(call -> { GostDto supported = new GostDto(); supported.setMsg("OK");
                        supported.setData(JSONObject.of("forwardProxyProtocol", 2, "proxyProtocolVersions", JSONArray.of(1, 2))); return supported; });
            socket.when(() -> WebSocketServer.send_msg(eq(12L), any(), eq("ForwardProxyProtocolCapabilities")))
                    .thenAnswer(call -> { GostDto old = new GostDto(); old.setMsg(revisionOne ? "OK" : "未知命令类型");
                        if (revisionOne) old.setData(JSONObject.of("forwardProxyProtocol", 1, "proxyProtocolVersions", JSONArray.of(1, 2)));
                        return old; });
            ForwardServiceImpl service = new ForwardServiceImpl();
            R create = ReflectionTestUtils.invokeMethod(service, "createGostServices", forward, new Tunnel(), null, nodeInfo, null);
            R update = ReflectionTestUtils.invokeMethod(service, "updateGostServices", forward, new Tunnel(), null, nodeInfo, null);
            R move = ReflectionTestUtils.invokeMethod(service, "updateGostServicesWithTunnelChange", forward, forward,
                    new Tunnel(), null, nodeInfo, null);
            for (R result : List.of(create, update, move)) {
                assertNotEquals(0, result.getCode()); assertTrue(result.getMsg().contains("old-second"));
                assertTrue(result.getMsg().contains("升级节点后端"));
            }
            socket.verify(() -> WebSocketServer.send_msg(anyLong(), any(), argThat(command -> !"ForwardProxyProtocolCapabilities".equals(command))), never());
        }
    }
}

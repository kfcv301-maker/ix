package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;

class TcpPingDiagnosisResponseTest {
    static Stream<Arguments> serviceResponses() {
        return Stream.of(ForwardServiceImpl.class, TunnelServiceImpl.class).flatMap(type -> Stream.of(
                Arguments.of(type, "missing payload", null, false, -1.0, 100.0),
                Arguments.of(type, "invalid payload type", "not an object", false, -1.0, 100.0),
                Arguments.of(type, "missing metrics", Map.of("success", true), false, -1.0, 100.0),
                Arguments.of(type, "string success", Map.of("success", "true", "averageTime", 1, "packetLoss", 0), false, -1.0, 100.0),
                Arguments.of(type, "valid map", Map.of("success", true, "averageTime", 15.5, "packetLoss", 50), true, 15.5, 50.0),
                Arguments.of(type, "valid JSONObject", JSONObject.of("success", true, "averageTime", 0, "packetLoss", 0), true, 0.0, 0.0),
                Arguments.of(type, "reported failure", Map.of("success", false, "errorMessage", "connection refused"), false, -1.0, 100.0)
        ));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("serviceResponses")
    void bothDiagnosisPathsKeepIdentityAndRequireActualConnectionEvidence(
            Class<?> type, String description, Object data, boolean success, double average, double loss) throws Exception {
        Object service = type.getDeclaredConstructor().newInstance();
        Node node = new Node();
        node.setId(26L);
        node.setName("Test entry");
        GostDto response = new GostDto();
        response.setMsg("OK");
        response.setData(data);
        long started = System.currentTimeMillis();
        try (var commands = mockStatic(WebSocketServer.class)) {
            commands.when(() -> WebSocketServer.send_msg(eq(26L), any(), eq("TcpPing"))).thenReturn(response);
            Object value = ReflectionTestUtils.invokeMethod(service, "performTcpPingDiagnosis", node, "198.51.100.1", 443, description);
            assertNotNull(value);
            BeanWrapperImpl result = new BeanWrapperImpl(value);
            assertEquals(success, result.getPropertyValue("success"));
            assertEquals(average, result.getPropertyValue("averageTime"));
            assertEquals(loss, result.getPropertyValue("packetLoss"));
            assertEquals(26L, result.getPropertyValue("nodeId"));
            assertEquals("Test entry", result.getPropertyValue("nodeName"));
            assertEquals("198.51.100.1", result.getPropertyValue("targetIp"));
            assertEquals(443, result.getPropertyValue("targetPort"));
            assertEquals(description, result.getPropertyValue("description"));
            assertTrue((long) result.getPropertyValue("timestamp") >= started);
            assertFalse(((String) result.getPropertyValue("message")).isBlank());
            commands.verify(() -> WebSocketServer.send_msg(eq(26L), any(), eq("TcpPing")));
        }
    }
}

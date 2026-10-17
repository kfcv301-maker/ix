package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TcpPingDiagnosisParserTest {
    static Stream<Arguments> malformedResponses() {
        return Stream.of(
                Arguments.of("absent data", null),
                Arguments.of("JSON text is not an object", "{\"success\":true,\"averageTime\":1,\"packetLoss\":0}"),
                Arguments.of("array", List.of(Map.of("success", true))),
                Arguments.of("boolean", true),
                Arguments.of("missing success", Map.of("averageTime", 1, "packetLoss", 0)),
                Arguments.of("string success", Map.of("success", "true", "averageTime", 1, "packetLoss", 0)),
                Arguments.of("numeric success", Map.of("success", 1, "averageTime", 1, "packetLoss", 0)),
                Arguments.of("null success", nullable("success", null)),
                Arguments.of("missing average", Map.of("success", true, "packetLoss", 0)),
                Arguments.of("missing loss", Map.of("success", true, "averageTime", 1)),
                Arguments.of("null average", nullable("averageTime", null)),
                Arguments.of("null loss", nullable("packetLoss", null)),
                Arguments.of("text average", nullable("averageTime", "1")),
                Arguments.of("text loss", nullable("packetLoss", "0")),
                Arguments.of("negative average", nullable("averageTime", -0.001)),
                Arguments.of("NaN average", nullable("averageTime", Double.NaN)),
                Arguments.of("infinite average", nullable("averageTime", Double.POSITIVE_INFINITY)),
                Arguments.of("negative infinite average", nullable("averageTime", Double.NEGATIVE_INFINITY)),
                Arguments.of("NaN loss", nullable("packetLoss", Double.NaN)),
                Arguments.of("infinite loss", nullable("packetLoss", Double.POSITIVE_INFINITY)),
                Arguments.of("negative loss", nullable("packetLoss", -0.001)),
                Arguments.of("loss above 100", nullable("packetLoss", 100.1)),
                Arguments.of("all attempts lost cannot be success", nullable("packetLoss", 100)),
                Arguments.of("overflowed JSON numeric average", nullable("averageTime", new BigDecimal("1e1000")))
        );
    }

    private static Map<String, Object> nullable(String key, Object value) {
        Map<String, Object> data = new HashMap<>(Map.of("success", true, "averageTime", 1, "packetLoss", 0));
        data.put(key, value);
        return data;
    }

    private static GostDto response(Object data) {
        GostDto response = new GostDto();
        response.setMsg("OK");
        response.setData(data);
        return response;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedResponses")
    void acknowledgementWithoutValidMeasurementFails(String name, Object data) {
        TcpPingDiagnosisParser.Result result = TcpPingDiagnosisParser.parse(response(data));
        assertFalse(result.success(), name);
        assertEquals(-1.0, result.averageTime());
        assertEquals(100.0, result.packetLoss());
        assertTrue(result.message().contains("数据无效"));
    }

    @Test void validJSONObjectPreservesMeasuredLatencyAndPartialLoss() {
        TcpPingDiagnosisParser.Result result = TcpPingDiagnosisParser.parse(response(JSONObject.of(
                "success", true, "averageTime", 12.75, "packetLoss", 25)));
        assertTrue(result.success());
        assertEquals(12.75, result.averageTime());
        assertEquals(25, result.packetLoss());
    }

    @Test void aPlainMapWithExplicitZeroLatencyRemainsValid() {
        TcpPingDiagnosisParser.Result result = TcpPingDiagnosisParser.parse(response(Map.of(
                "success", true, "averageTime", BigDecimal.ZERO, "packetLoss", 0L)));
        assertTrue(result.success());
        assertEquals(0.0, result.averageTime());
        assertEquals(0.0, result.packetLoss());
    }

    @Test void explicitConnectionFailureNeedsNoSuccessMetricsAndRetainsItsReason() {
        TcpPingDiagnosisParser.Result result = TcpPingDiagnosisParser.parse(response(Map.of(
                "success", false, "errorMessage", "connection refused")));
        assertFalse(result.success());
        assertEquals("connection refused", result.message());
        assertEquals(-1.0, result.averageTime());
        assertEquals(100.0, result.packetLoss());
    }

    @Test void aFailureWithoutATextReasonStillHasAnExplicitFailureMessage() {
        for (Object data : List.of(Map.of("success", false),
                Map.of("success", false, "errorMessage", " "),
                Map.of("success", false, "errorMessage", Map.of("unexpected", "object")))) {
            TcpPingDiagnosisParser.Result result = TcpPingDiagnosisParser.parse(response(data));
            assertFalse(result.success());
            assertEquals("TCP连接失败", result.message());
        }
    }

    @Test void transportFailureAndNoResponseCannotBeOverriddenBySuccessData() {
        GostDto offline = response(Map.of("success", true, "averageTime", 2, "packetLoss", 0));
        offline.setMsg("节点离线");
        assertEquals(new TcpPingDiagnosisParser.Result(false, "节点离线", -1, 100), TcpPingDiagnosisParser.parse(offline));
        assertEquals(new TcpPingDiagnosisParser.Result(false, "节点无响应", -1, 100), TcpPingDiagnosisParser.parse(null));
        offline.setMsg(null);
        assertFalse(TcpPingDiagnosisParser.parse(offline).success());
        assertFalse(TcpPingDiagnosisParser.parse(offline).message().isBlank());
    }
}

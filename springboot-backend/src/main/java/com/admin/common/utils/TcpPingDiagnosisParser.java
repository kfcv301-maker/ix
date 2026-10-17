package com.admin.common.utils;

import com.admin.common.dto.GostDto;

import java.util.Map;

/** A command acknowledgement is not evidence that a TCP connection succeeded. */
public final class TcpPingDiagnosisParser {
    private static final String INVALID_RESPONSE = "节点返回的TCP诊断数据无效，请重试或更新节点后端";

    private TcpPingDiagnosisParser() {}

    public record Result(boolean success, String message, double averageTime, double packetLoss) {}

    public static Result parse(GostDto response) {
        if (response == null) return failure("节点无响应");
        if (!"OK".equals(response.getMsg())) {
            return failure(nonblank(response.getMsg(), "节点TCP诊断请求失败"));
        }
        // JSONObject implements Map. Do not coerce strings, numbers, or missing
        // fields into a successful result through JSON getter defaults.
        if (!(response.getData() instanceof Map<?, ?> data)
                || !(data.get("success") instanceof Boolean success)) {
            return failure(INVALID_RESPONSE);
        }
        if (!success) {
            Object message = data.get("errorMessage");
            return failure(message instanceof String text ? nonblank(text, "TCP连接失败") : "TCP连接失败");
        }
        if (!(data.get("averageTime") instanceof Number average)
                || !(data.get("packetLoss") instanceof Number loss)) {
            return failure(INVALID_RESPONSE);
        }
        double averageTime = average.doubleValue();
        double packetLoss = loss.doubleValue();
        if (!Double.isFinite(averageTime) || averageTime < 0
                || !Double.isFinite(packetLoss) || packetLoss < 0 || packetLoss >= 100) {
            return failure(INVALID_RESPONSE);
        }
        return new Result(true, "TCP连接成功", averageTime, packetLoss);
    }

    private static Result failure(String message) {
        return new Result(false, message, -1.0, 100.0);
    }

    private static String nonblank(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}

package com.admin.common.utils;

import com.admin.common.dto.ForwardDto;
import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

public final class ForwardCreateRequest {
    private ForwardCreateRequest() { }

    public static String fingerprint(ForwardDto dto) {
        if (dto.getRequestId() == null) return null; // Legacy API clients remain supported.
        if (!dto.getRequestId().matches("[A-Za-z0-9_-]{16,64}")) {
            throw new IllegalArgumentException("创建请求标识格式不正确");
        }
        String input = JSON.toJSONString(Arrays.asList(dto.getName(), dto.getTunnelId(), dto.getInPort(),
                dto.getRemoteAddr(), dto.getVpsHostId(), dto.getInterfaceName(), dto.getStrategy(),
                GostUtil.normalizeProxyProtocol(dto.getProxyProtocol())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

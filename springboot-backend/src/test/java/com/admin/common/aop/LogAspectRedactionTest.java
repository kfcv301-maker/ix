package com.admin.common.aop;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LogAspectRedactionTest {
    private String redact(String input) {
        return ReflectionTestUtils.invokeMethod(new LogAspect(), "redactSensitiveValues", input);
    }

    @Test
    void escapedQuoteAndBackslashCannotExposeTheRestOfAPassword() {
        String result = redact(JSON.toJSONString(Map.of("user", "alice", "password", "prefix\\\"still-secret")));
        assertFalse(result.contains("still-secret"));
        assertEquals("***", JSON.parseObject(result).getString("password"));
        assertEquals("alice", JSON.parseObject(result).getString("user"));
    }

    @Test
    void sensitiveValuesAreRemovedFromNestedObjectsAndArrays() {
        String result = redact(JSON.toJSONString(Map.of("items", List.of(
            Map.of("sshPrivateKey", "BEGIN\nsecret-key\nEND", "port", 22),
            Map.of("Authorization", "Bearer hidden-token", "api_key", "hidden-api-key")))));
        assertFalse(result.contains("secret-key"));
        assertFalse(result.contains("hidden-token"));
        assertFalse(result.contains("hidden-api-key"));
        assertTrue(result.contains("22"));
    }

    @Test
    void malformedSerializedDataIsOmittedInsteadOfReturnedUnredacted() {
        assertFalse(redact("{\"password\":\"partial-secret").contains("partial-secret"));
    }

    @Test
    void SerializedJsonInsideAStringIsAlsoRedacted() {
        String result = redact(JSON.toJSONString(Map.of("payload", "{\"token\":\"nested-secret\"}")));
        assertFalse(result.contains("nested-secret"));
    }
}

package com.admin.common.utils;

import com.admin.common.dto.ForwardDto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardCreateRequestTest {
    @Test void oldClientsNeedNoKeyAndInvalidKeysAreRejected() {
        ForwardDto dto = new ForwardDto();
        assertNull(ForwardCreateRequest.fingerprint(dto));
        dto.setRequestId("bad key");
        assertThrows(IllegalArgumentException.class, () -> ForwardCreateRequest.fingerprint(dto));
    }
    @Test void payloadFingerprintNormalizesProxyDefaultAndDetectsChangedDestination() {
        ForwardDto dto = new ForwardDto(); dto.setRequestId("request-000000001");
        dto.setName("test"); dto.setTunnelId(1); dto.setRemoteAddr("example.com:443");
        String original = ForwardCreateRequest.fingerprint(dto);
        dto.setProxyProtocol(0);
        assertEquals(original, ForwardCreateRequest.fingerprint(dto));
        dto.setRemoteAddr("other.example:443");
        assertNotEquals(original, ForwardCreateRequest.fingerprint(dto));
    }
}

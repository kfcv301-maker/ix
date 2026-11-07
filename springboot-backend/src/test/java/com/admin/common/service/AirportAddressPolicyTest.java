package com.admin.common.service;

import com.admin.config.AirportPeerSource;
import jakarta.servlet.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirportAddressPolicyTest {
    @Test void blocksPrivateMetadataDocumentationAndAmbiguousAddresses() {
        for(String value:new String[]{"127.0.0.1","10.1.2.3","169.254.169.254","100.64.0.1","192.0.2.1","203.0.113.3","198.51.100.1","224.0.0.1","0.1.2.3","012.3.4.5","1.2.3","localhost","::1","fc00::1"})
            assertFalse(AirportAddressPolicy.publicV4(value),value);
        assertTrue(AirportAddressPolicy.publicV4("8.8.4.4"));
    }
    @Test void requiresFixedHttpsDestinationWithoutCredentialOrRedirectPaths() {
        for(String value:new String[]{"http://example.com/api/v1/node-address-sync/report","https://u:p@example.com/api/v1/node-address-sync/report","https://example.com:8443/api/v1/node-address-sync/report","https://example.com/api/v1/node-address-sync/report?token=x","https://example.com/other","https://example.com/api/v1/node-address-sync/report#x"})
            assertThrows(IllegalArgumentException.class,()->AirportAddressPolicy.endpoint(value));
        assertEquals("https://example.com/api/v1/node-address-sync/report",AirportAddressPolicy.endpoint("https://EXAMPLE.com:443/api/v1/node-address-sync/report"));
    }
    @Test void offIsDefaultAndUntrustedForwardedHeadersNeverWin() {
        assertNull(new AirportPeerSource("off","").resolve("8.8.4.4","8.8.8.8"));
        assertEquals("8.8.4.4",new AirportPeerSource("direct","").resolve("8.8.4.4","8.8.8.8"));
        assertNull(new AirportPeerSource("trusted-proxy","127.0.0.1/32").resolve("8.8.8.8","8.8.4.4"));
    }
    @Test void stripsTrustedHopsFromRightAndRejectsIpv6RatherThanGuessing() {
        var policy=new AirportPeerSource("trusted-proxy","127.0.0.1/32,104.16.0.0/13");
        assertEquals("8.8.4.4",policy.resolve("127.0.0.1","6.6.6.6, 8.8.4.4, 104.16.1.2"));
        assertNull(policy.resolve("127.0.0.1","2406:da18::1,104.16.1.2"));
        assertNull(policy.resolve("127.0.0.1","169.254.169.254,104.16.1.2"));
    }
    @Test void frameworkHeaderWrapperCannotReplaceUnderlyingTransportIdentity() {
        var raw=new MockHttpServletRequest();raw.setRemoteAddr("127.0.0.1");raw.addHeader("X-Forwarded-For","8.8.4.4, 104.16.1.2");
        var wrapper=new HttpServletRequestWrapper(raw) {
            @Override public String getRemoteAddr() { return "6.6.6.6"; }
            @Override public String getHeader(String key) { return null; }
        };
        assertEquals("8.8.4.4",new AirportPeerSource("trusted-proxy","127.0.0.1,104.16.0.0/13").resolveRequest(wrapper));
    }
    @Test void receiptRequiresMatchingIpAndKnownStatus() {
        assertEquals("synced",AirportSyncTransport.receipt("{\"data\":{\"status\":\"duplicate\",\"ip\":\"8.8.4.4\"}}","8.8.4.4"));
        for(String body:new String[]{"{}","OK","{\"data\":{\"status\":\"updated\",\"ip\":\"6.6.6.6\"}}"}) assertEquals("retry_receipt",AirportSyncTransport.receipt(body,"8.8.4.4"));
        assertEquals("retry_receipt",AirportSyncTransport.receipt("{\"code\":1,\"data\":{\"status\":\"updated\",\"ip\":\"8.8.4.4\"}}","8.8.4.4"));
    }
    @Test void rejectsMixedPublicAndPrivateDnsAnswersBeforeOpeningHttpConnection() {
        var transport=new AirportSyncTransport() {
            @Override protected java.net.InetAddress[] resolve(String host) {
                return new java.net.InetAddress[]{AirportAddressPolicy.literal("8.8.8.8"),AirportAddressPolicy.literal("169.254.169.254")};
            }
        };
        try { assertEquals("blocked_destination",transport.send("https://airport.example/api/v1/node-address-sync/report","binding","isolated-fixture","8.8.4.4",1800000000)); }
        finally { transport.close(); }
    }
}

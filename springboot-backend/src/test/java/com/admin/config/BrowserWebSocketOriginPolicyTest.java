package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BrowserWebSocketOriginPolicyTest {
    @Test void directNonstandardPortAndOuterHttpsBothPassTheTwoOriginChecks() throws Exception {
        assertHandshake("http://panel.example:6366", "http", 6366, "http");
        assertHandshake("https://panel.example", "http", 80, "https");
    }

    private void assertHandshake(String origin, String scheme, int port, String forwardedScheme) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme); request.setServerName("panel.example"); request.setServerPort(port);
        request.setRequestURI("/system-info"); request.addHeader("Origin", origin);
        request.addHeader("X-Forwarded-Proto", forwardedScheme);
        request.addHeader("X-Forwarded-Host", java.net.URI.create(origin).getRawAuthority());
        BrowserWebSocketOriginPolicy policy = new BrowserWebSocketOriginPolicy();
        ReflectionTestUtils.setField(policy, "allowedOrigins", "");
        AtomicBoolean passed = new AtomicBoolean();
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            HttpServletRequest browserRequest = (HttpServletRequest) req;
            assertTrue(policy.isAllowed(browserRequest), browserRequest.getScheme() + "://"
                    + browserRequest.getServerName() + ":" + browserRequest.getServerPort());
            try {
                assertTrue(new OriginHandshakeInterceptor().beforeHandshake(new ServletServerHttpRequest(browserRequest),
                        new ServletServerHttpResponse((jakarta.servlet.http.HttpServletResponse) res),
                        mock(WebSocketHandler.class), new HashMap<>()));
            } catch (Exception exception) { throw new jakarta.servlet.ServletException(exception); }
            passed.set(true);
        });
        assertTrue(passed.get());
    }

    @Test void sameHostDoesNotAllowADifferentProtocolOrMalformedOrigin() {
        BrowserWebSocketOriginPolicy policy = new BrowserWebSocketOriginPolicy();
        ReflectionTestUtils.setField(policy, "allowedOrigins", "");
        for (String origin : new String[]{"http://panel.example", "https://panel.example?x=1", "https://other.example"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setScheme("https"); request.setServerName("panel.example"); request.setServerPort(443);
            request.addHeader("Origin", origin);
            assertFalse(policy.isAllowed(request));
        }
    }
}

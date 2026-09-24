package com.admin.config;

import com.admin.service.VpsHostService;
import com.admin.service.VpsTerminalTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;

/** Authorizes a VPS terminal before a WebSocket is upgraded. */
@Component
public class VpsTerminalHandshakeInterceptor extends HttpSessionHandshakeInterceptor {

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private VpsTerminalTicketService vpsTerminalTicketService;

    @Value("${vps.terminal.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return forbidden(response);
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        if (!isAllowedOrigin(servletRequest)) return forbidden(response);
        VpsTerminalTicketService.TerminalAccess access = vpsTerminalTicketService.consume(
                servletRequest.getParameter("ticket"));
        if (access == null || !vpsHostService.canOperate(access.getUserId(), access.isAdministrator(), access.getVpsId())) {
            return forbidden(response);
        }

        attributes.put("vpsId", access.getVpsId());
        attributes.put("userId", access.getUserId());
        attributes.put("administrator", access.isAdministrator());
        return true;
    }

    private boolean forbidden(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    private boolean isAllowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.trim().isEmpty()) return false;
        final URI originUri;
        try {
            originUri = URI.create(origin.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!"http".equalsIgnoreCase(originUri.getScheme()) && !"https".equalsIgnoreCase(originUri.getScheme())) {
            return false;
        }
        if (originUri.getHost() == null || originUri.getUserInfo() != null
                || (originUri.getPath() != null && !originUri.getPath().isEmpty() && !"/".equals(originUri.getPath()))) {
            return false;
        }

        String normalizedOrigin = origin.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        String originConfiguration = allowedOrigins == null ? "" : allowedOrigins;
        String[] configuredOrigins = Stream.of(originConfiguration.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.replaceAll("/+$", "").toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        if (configuredOrigins.length > 0) {
            return Arrays.asList(configuredOrigins).contains(normalizedOrigin);
        }

        String requestHost = request.getHeader("Host");
        return requestHost != null && originUri.getRawAuthority() != null
                && originUri.getRawAuthority().equalsIgnoreCase(requestHost.trim());
    }
}

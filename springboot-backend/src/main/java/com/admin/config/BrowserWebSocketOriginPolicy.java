package com.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/** Same-origin by default; deployments may provide an explicit browser allow-list. */
@Component
public class BrowserWebSocketOriginPolicy {

    @Value("${realtime.allowed-origins:}")
    private String allowedOrigins;

    public boolean isAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.trim().isEmpty()) return false;
        final URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) return false;
        String normalized = origin.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        String[] configured = Arrays.stream((allowedOrigins == null ? "" : allowedOrigins).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> value.replaceAll("/+$", "").toLowerCase(Locale.ROOT)).toArray(String[]::new);
        if (configured.length > 0) return Arrays.asList(configured).contains(normalized);
        int originPort = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : uri.getPort();
        return uri.getScheme().equalsIgnoreCase(request.getScheme())
                && uri.getHost().equalsIgnoreCase(request.getServerName())
                && originPort == request.getServerPort();
    }

    private int defaultPort(String scheme) { return "https".equalsIgnoreCase(scheme) ? 443 : 80; }
}

package com.admin.controller;

import com.admin.common.service.NodeAgentConfigService;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** These endpoints authenticate node capabilities themselves, never browser JWTs. */
@RestController
public class NodeAgentConfigController {
    private final NodeAgentConfigService configs;
    public NodeAgentConfigController(NodeAgentConfigService configs) { this.configs = configs; }

    @GetMapping(value = "/api/v1/node/install-config/{nodeId:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> installation(@PathVariable("nodeId") Long nodeId, HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return response(HttpStatus.METHOD_NOT_ALLOWED, null);
        try {
            JSONObject payload = configs.installationConfig(nodeId, bearer(request));
            return response(payload == null ? HttpStatus.UNAUTHORIZED : HttpStatus.OK, payload);
        } catch (IllegalStateException exception) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, null);
        }
    }

    @GetMapping(value = "/api/v1/node/runtime-config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runtime(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return response(HttpStatus.METHOD_NOT_ALLOWED, null);
        try {
            JSONObject payload = configs.authenticatedRuntimeConfig(bearer(request));
            return response(payload == null ? HttpStatus.UNAUTHORIZED : HttpStatus.OK, payload);
        } catch (IllegalStateException exception) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, null);
        }
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private ResponseEntity<?> response(HttpStatus status, Object payload) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(payload == null ? java.util.Map.of("error", "node_configuration_unavailable") : payload);
    }
}

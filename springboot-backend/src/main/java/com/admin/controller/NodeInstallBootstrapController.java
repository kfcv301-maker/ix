package com.admin.controller;

import com.admin.service.NodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

/** The single-use capability is the only credential accepted by this endpoint. */
@RestController
public class NodeInstallBootstrapController {
    private final NodeService nodeService;

    public NodeInstallBootstrapController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @GetMapping(value = "/api/v1/node/bootstrap/{token:[A-Za-z0-9_-]{32}}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> claim(@PathVariable("token") String token, HttpServletRequest request) {
        // Spring maps HEAD to GET automatically; a link preview must not consume a ticket.
        if (!"GET".equals(request.getMethod())) {
            return response(HttpStatus.METHOD_NOT_ALLOWED, "GET required\n");
        }
        String script = nodeService.claimInstallScript(token);
        return script == null
                ? response(HttpStatus.GONE, "安装命令已失效，请在面板重新获取\n")
                : response(HttpStatus.OK, script);
    }

    @GetMapping(value = "/api/v1/node/install-script/{nodeId:[0-9]+}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> reusable(@PathVariable("nodeId") Long nodeId,
            @RequestParam("issuerId") Long issuerId, @RequestParam("panelUrl") String panelUrl,
            HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return response(HttpStatus.METHOD_NOT_ALLOWED, "GET required\n");
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        String script = nodeService.getReusableInstallScript(nodeId, issuerId, panelUrl, token);
        return script == null ? response(HttpStatus.FORBIDDEN, "安装授权无效\n") : response(HttpStatus.OK, script);
    }

    private ResponseEntity<String> response(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}

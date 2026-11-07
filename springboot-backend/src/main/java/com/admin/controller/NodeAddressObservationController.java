package com.admin.controller;

import com.admin.common.service.AirportSyncService;
import com.admin.common.service.NodeAgentConfigService;
import com.admin.entity.Node;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/node/address-observation")
public class NodeAddressObservationController {
    private final NodeAgentConfigService configs;
    private final AirportSyncService sync;
    public NodeAddressObservationController(NodeAgentConfigService configs,AirportSyncService sync) { this.configs=configs;this.sync=sync; }
    private Node authenticate(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        return configs.authenticatedNode(header!=null && header.startsWith("Bearer ")?header.substring(7):null);
    }
    private ResponseEntity<?> response(int status,Object data) {
        return ResponseEntity.status(status).header("Cache-Control","no-store, private").body(data);
    }
    @GetMapping public ResponseEntity<?> policy(HttpServletRequest request) {
        Node node=authenticate(request);
        return node==null?response(401,Map.of("error","unauthorized")):response(200,Map.of("code",0,"data",sync.observationPolicy(node.getId())));
    }
    @PostMapping public ResponseEntity<?> report(HttpServletRequest request,@RequestBody AirportSyncService.Report report) {
        Node node=authenticate(request);
        if (node==null) return response(401,Map.of("error","unauthorized"));
        try { return sync.report(node,report)?response(200,Map.of("code",0,"data",Map.of("accepted",true,"ip",report.ip()))):response(403,Map.of("error","disabled")); }
        catch (IllegalArgumentException invalid) { return response(422,Map.of("error","invalid_observation")); }
    }
}

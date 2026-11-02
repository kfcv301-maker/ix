package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.service.NodeIpHistoryService;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/node/ip-history")
public class NodeIpHistoryController {
    private final NodeIpHistoryService service;
    public NodeIpHistoryController(NodeIpHistoryService service) { this.service=service; }
    public record HistoryRequest(Long id, Long beforeId) {}

    @PostMapping
    public R history(@RequestBody HistoryRequest request) {
        try { return R.ok(service.history(Long.valueOf(JwtUtil.getUserIdFromToken()),request.id(),request.beforeId())); }
        catch (SecurityException denied) { return R.err(403,denied.getMessage()); }
        catch (IllegalArgumentException invalid) { return R.err(400,"无效的节点或分页参数"); }
    }

    @PostMapping("/summaries")
    public R summaries() {
        try { return R.ok(service.summaries(Long.valueOf(JwtUtil.getUserIdFromToken()))); }
        catch (SecurityException denied) { return R.err(403,denied.getMessage()); }
    }
}

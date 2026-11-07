package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.service.AirportSyncService;
import com.admin.common.service.AirportReporterInstaller;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/node/airport-sync")
public class AirportSyncController {
    private final AirportSyncService service;
    private final AirportReporterInstaller installer;
    public AirportSyncController(AirportSyncService service,AirportReporterInstaller installer) { this.service=service;this.installer=installer; }
    public record Selection(Long nodeId,Long revision) { }
    @PostMapping("/get") public R get(@RequestBody Selection selected) {
        return invoke(()->service.get(Long.valueOf(JwtUtil.getUserIdFromToken()),selected.nodeId()));
    }
    @PostMapping("/save") public R save(@RequestBody AirportSyncService.Settings settings) {
        return invoke(()->service.save(Long.valueOf(JwtUtil.getUserIdFromToken()),settings));
    }
    @PostMapping("/remove") public R remove(@RequestBody Selection selected) {
        return invoke(()->{service.remove(Long.valueOf(JwtUtil.getUserIdFromToken()),selected.nodeId(),selected.revision());return null;});
    }
    @PostMapping("/observer") public R observer(@RequestBody Selection selected) {
        return invoke(()->{
            service.get(Long.valueOf(JwtUtil.getUserIdFromToken()),selected.nodeId());
            Object bundle=installer.installation(selected.nodeId());
            if (bundle==null) throw new IllegalArgumentException("请先保存并启用 AWS 模式");
            return bundle;
        });
    }
    private R invoke(java.util.function.Supplier<Object> operation) {
        try { return R.ok(operation.get()); }
        catch (SecurityException denied) { return R.err(403,denied.getMessage()); }
        catch (IllegalArgumentException invalid) { return R.err(invalid.getMessage()); }
        catch (RuntimeException unavailable) { return R.err("机场同步设置暂时不可用，请稍后重试"); }
    }
}

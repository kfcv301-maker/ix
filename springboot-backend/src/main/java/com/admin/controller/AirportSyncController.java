package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.service.AirportSyncService;
import com.admin.common.service.AirportReporterInstaller;
import com.admin.common.service.AirportConnectionService;
import com.admin.common.service.AirportObserverDeploymentService;
import com.admin.common.utils.JwtUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/node/airport-sync")
public class AirportSyncController {
    private final AirportSyncService service;
    private final AirportReporterInstaller installer;
    private final AirportConnectionService connections;
    private final AirportObserverDeploymentService deployment;
    public AirportSyncController(AirportSyncService service,AirportReporterInstaller installer,AirportConnectionService connections,AirportObserverDeploymentService deployment) { this.service=service;this.installer=installer;this.connections=connections;this.deployment=deployment; }
    public record ObserverInstall(Long nodeId,Long hostId) { }
    @PostMapping("/observer/targets") public R observerTargets(@RequestBody Selection s) {
        return invoke(()->deployment.targets(Long.valueOf(JwtUtil.getUserIdFromToken()),s.nodeId()));
    }
    @PostMapping("/observer/install") public R installObserver(@RequestBody ObserverInstall s) {
        return invoke(()->deployment.start(Long.valueOf(JwtUtil.getUserIdFromToken()),s.nodeId(),s.hostId()));
    }
    public record ConnectionSelection(String id,long revision) { }
    @PostMapping("/connections") public R connections() {
        return invoke(()->connections.list(Long.valueOf(JwtUtil.getUserIdFromToken())));
    }
    @PostMapping("/connection/save") public R saveConnection(@RequestBody AirportConnectionService.Settings settings) {
        return invoke(()->connections.save(Long.valueOf(JwtUtil.getUserIdFromToken()),settings));
    }
    @PostMapping("/connection/check") public R checkConnection(@RequestBody ConnectionSelection s) {
        return invoke(()->connections.check(Long.valueOf(JwtUtil.getUserIdFromToken()),s.id(),s.revision()));
    }
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

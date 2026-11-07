package com.admin.common.service;

import com.admin.mapper.NodeMapper;
import com.admin.service.VpsHostService;
import com.admin.service.VpsSshService;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Optional one-time online preparation for old installations; never reinstalls the Agent. */
@Service
public class AirportObserverDeploymentService {
    private final AirportSyncService sync;
    private final AirportReporterInstaller installer;
    private final NodeMapper nodes;
    private final VpsHostService hosts;
    private final VpsSshService ssh;
    private final Map<Long,String> states=new ConcurrentHashMap<>();
    private final Set<Long> busyHosts=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"airport-observer-install");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public AirportObserverDeploymentService(AirportSyncService sync,AirportReporterInstaller installer,NodeMapper nodes,VpsHostService hosts,VpsSshService ssh) {
        this.sync=sync;this.installer=installer;this.nodes=nodes;this.hosts=hosts;this.ssh=ssh;
    }
    public Map<String,Object> targets(Long userId,Long nodeId) {
        sync.get(userId,nodeId);
        return Map.of("status",states.getOrDefault(nodeId,"idle"),"hosts",hosts.listAccessibleHosts(userId,true).stream()
                .filter(h->Objects.equals(h.getStatus(),1) && hosts.isFingerprintVerified(h))
                .map(h->Map.of("id",h.getId(),"name",h.getName(),"host",h.getHost())).toList());
    }
    public synchronized String start(Long userId,Long nodeId,Long hostId) {
        sync.get(userId,nodeId);
        if (!sync.needsReporter(nodeId)) throw new IllegalArgumentException("请先保存并启用 AWS 上报");
        var host=hosts.findOperableHost(userId,true,hostId);
        if (host==null || !Objects.equals(host.getStatus(),1) || !hosts.isFingerprintVerified(host)) throw new IllegalArgumentException("请先在 VPS 管理中录入并确认此机器的 SSH 身份");
        if ("running".equals(states.get(nodeId)) || !busyHosts.add(hostId)) throw new IllegalArgumentException("此节点或服务器已有启用任务");
        if (states.size()>1024) states.entrySet().removeIf(e->!"running".equals(e.getValue()));
        states.put(nodeId,"running");
        try { executor.execute(()->run(userId,nodeId,hostId)); }
        catch (RejectedExecutionException full) { busyHosts.remove(hostId);states.put(nodeId,"failed");throw new IllegalArgumentException("启用队列繁忙，请稍后重试"); }
        return "running";
    }
    void run(Long userId,Long nodeId,Long hostId) {
        try {
            sync.get(userId,nodeId);
            if (!sync.needsReporter(nodeId)) throw new IllegalArgumentException();
            var host=hosts.findOperableHost(userId,true,hostId);var node=nodes.selectById(nodeId);
            if (host==null || node==null || !Objects.equals(host.getStatus(),1) || !hosts.isFingerprintVerified(host)) throw new IllegalArgumentException();
            var bundle=installer.bundle();
            var result=ssh.installAddressObserver(host,hosts.decryptSshPassword(host),bundle.getString("scriptBase64"),AirportSyncState.digest(node.getSecret()));
            states.put(nodeId,result.isSucceeded()?"ready":"failed");
        } catch (VpsSshService.HostFingerprintChangedException changed) { states.put(nodeId,"fingerprint_changed"); }
        catch (Exception failure) { states.put(nodeId,"failed"); }
        finally { busyHosts.remove(hostId); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}

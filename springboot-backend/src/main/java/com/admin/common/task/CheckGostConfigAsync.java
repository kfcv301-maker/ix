package com.admin.common.task;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CheckGostConfigAsync {
    @Resource
    private com.admin.common.service.UserTunnelAliasService userTunnelAliasService;

    private static final long DUPLICATE_INVENTORY_WINDOW_MILLIS = 20_000L;

    /**
     * An Agent reports its complete inventory immediately after reconnecting.
     * Keep a short fingerprint per node so an overlapping startup report does
     * not trigger the same full database reconciliation twice. The periodic
     * ten-minute report is well outside this window and remains authoritative.
     */
    private final Map<Long, InventoryFingerprint> recentInventories = new ConcurrentHashMap<>();

    @Resource
    private NodeService nodeService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private SpeedLimitService speedLimitService;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    @Lazy
    private UserTunnelService userTunnelService;

    @Resource
    @Lazy
    private UserService userService;

    @Resource
    @Lazy
    private ForwardPauseTaskService forwardPauseTaskService;

    @Resource
    @Lazy
    private ForwardSyncTaskService forwardSyncTaskService;

    @Resource
    private TunnelEntryNodeMapper tunnelEntryNodeMapper;



    /**
     * 清理孤立的Gost配置项
     */
    @Async("nodeConfigExecutor")
    public void cleanNodeConfigs(String node_id, GostConfigDto gostConfig) {
        if (gostConfig == null) {
            return;
        }
        Node node = nodeService.getById(node_id);
        if (node != null) {
            if (isDuplicateInventory(node.getId(), gostConfig)) {
                log.debug("节点 {} 的配置清单未变化，跳过重复全量对账", node.getId());
            } else {
                cleanOrphanedServices(gostConfig, node);
                cleanOrphanedChains(gostConfig, node);
                cleanOrphanedLimiters(gostConfig, node);
                // Restore limiters first. A recovered service that references a
                // limiter before it exists can start without its assigned cap.
                syncLimiters(gostConfig, node);
                // 重装节点会让 GOST 侧的运行时服务消失，但数据库中的转发记录仍然存在。
                // 配置上报发生在 WebSocket 建立之后，正是无人工编辑即可恢复缺失服务的安全时机。
                syncMissingForwards(gostConfig, node);
            }
            // Run durable quota/expiry pauses after the inventory has been
            // reconciled. This avoids a reconnect briefly restoring a service
            // while a prior pause command was waiting for that node.
            forwardPauseTaskService.retryPendingForNode(node.getId());
            forwardSyncTaskService.retryPendingForNode(node.getId());
        }
    }

    /**
     * 清理孤立的服务
     */
    private void cleanOrphanedServices(GostConfigDto gostConfig, Node node) {
        if (gostConfig.getServices() == null) {
            return;
        }

        Map<Long, Forward> forwardsById = loadForwardsByConfigItems(gostConfig.getServices());
        for (ConfigItem service : gostConfig.getServices()) {
            if (service == null) {
                continue;
            }
            safeExecute(() -> {

                if (!Objects.equals(service.getName(), "web_api")){
                    String[] serviceIds = parseServiceName(service.getName());
                    if (serviceIds.length == 4) {
                        String forwardId = serviceIds[0];
                        String userId = serviceIds[1];
                        String userTunnelId = serviceIds[2];
                        String type = serviceIds[3];
                        Long forwardRecordId = parseForwardId(forwardId);
                        if (forwardRecordId == null) {
                            return;
                        }
                        Forward forward = forwardsById.get(forwardRecordId);

                        String baseServiceName = forwardId + "_" + userId + "_" + userTunnelId;
                        if (Objects.equals(type, "tcp")) { // 只处理TCP，避免重复处理
                            if (forward == null) {
                                log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                                GostUtil.DeleteService(node.getId(), baseServiceName);
                            }
                        }


                        if (Objects.equals(type, "tls")) {
                            if (forward == null) {
                                log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                                GostUtil.DeleteRemoteService(node.getId(), baseServiceName);
                            }
                        }

                    }
                }


            }, "清理服务 " + service.getName());
        }

    }

    /**
     * 清理孤立的链
     */
    private void cleanOrphanedChains(GostConfigDto gostConfig, Node node) {
        if (gostConfig.getChains() == null) {
            return;
        }
        

        Map<Long, Forward> forwardsById = loadForwardsByConfigItems(gostConfig.getChains());
        for (ConfigItem chain : gostConfig.getChains()) {
            if (chain == null) {
                continue;
            }
            safeExecute(() -> {
                String[] serviceIds = parseServiceName(chain.getName());
                if (serviceIds.length == 4) {
                    String forwardId = serviceIds[0];
                    String userId = serviceIds[1];
                    String userTunnelId = serviceIds[2];
                    String type = serviceIds[3];
                    Long forwardRecordId = parseForwardId(forwardId);
                    
                    if (forwardRecordId != null && Objects.equals(type, "chains")) {
                        Forward forward = forwardsById.get(forwardRecordId);
                        if (forward == null) {
                            log.info("删除孤立的链: {} (节点: {})", chain.getName(), node.getId());
                            GostUtil.DeleteChains(node.getId(), forwardId+"_"+userId+"_"+userTunnelId);
                        }
                    }
                }
            }, "清理链 " + chain.getName());
        }
    }

    /**
     * 清理孤立的限流器
     */
    private void cleanOrphanedLimiters(GostConfigDto gostConfig, Node node) {
        if (gostConfig.getLimiters() == null) {
            return;
        }
        

        Set<Long> reportedLimiterIds = new HashSet<>();
        for (ConfigItem limiter : gostConfig.getLimiters()) {
            if (limiter == null || limiter.getName() == null) {
                continue;
            }
            try {
                reportedLimiterIds.add(Long.parseLong(limiter.getName()));
            } catch (NumberFormatException exception) {
                log.warn("节点 {} 上报了非法限速器名称 {}", node.getId(), limiter.getName());
            }
        }
        Set<Long> existingLimiterIds = new HashSet<>();
        if (!reportedLimiterIds.isEmpty()) {
            for (SpeedLimit speedLimit : speedLimitService.listByIds(reportedLimiterIds)) {
                existingLimiterIds.add(speedLimit.getId());
            }
        }
        for (ConfigItem limiter : gostConfig.getLimiters()) {
            if (limiter == null) {
                continue;
            }
            safeExecute(() -> {
                if (limiter == null || limiter.getName() == null) {
                    return;
                }
                Long limiterId;
                try {
                    limiterId = Long.parseLong(limiter.getName());
                } catch (NumberFormatException exception) {
                    return;
                }
                if (!existingLimiterIds.contains(limiterId)) {
                    log.info("删除孤立的限流器: {} (节点: {})", limiter.getName(), node.getId());
                    GostUtil.DeleteLimiters(node.getId(), limiterId);
                }
            }, "清理限流器 " + limiter.getName());
        }
    }

    /**
     * 同步限流器
     */
    private void syncLimiters(GostConfigDto gostConfig, Node node) {
        List<Tunnel> tunnelList = getIngressTunnels(node.getId());
        if (tunnelList == null || tunnelList.isEmpty()) return;
        safeExecute(() -> {
            List<Long> tunnelIds = new ArrayList<>();
            for (Tunnel tunnel : tunnelList) {
                tunnelIds.add(tunnel.getId());
            }
            List<SpeedLimit> speedLimits = speedLimitService.list(
                    new QueryWrapper<SpeedLimit>().in("tunnel_id", tunnelIds));
            if (speedLimits != null && !speedLimits.isEmpty()) {
                List<ConfigItem> limiters = gostConfig.getLimiters();
                Set<Long> limiterIds = new HashSet<>();
                if (limiters != null) {
                    for (ConfigItem limiter : limiters) {
                        try {
                            limiterIds.add(Long.valueOf(limiter.getName()));
                        } catch (NumberFormatException ignored) {
                            log.warn("节点 {} 上报了非法限速器名称 {}", node.getId(), limiter.getName());
                        }
                    }
                }
                for (SpeedLimit speedLimit : speedLimits) {
                    if (!limiterIds.contains(speedLimit.getId())) {
                        R result = speedLimitService.ensureLimiterOnNode(speedLimit.getId(), node.getId());
                        if (result.getCode() != 0) {
                            log.warn("节点 {} 恢复限速器 {} 失败: {}", node.getId(), speedLimit.getId(), result.getMsg());
                        }
                    }
                }
            }
        }, "同步限流器 ");
    }

    /**
     * 恢复节点重装后丢失的活动转发。
     *
     * 只处理配置上报中完全缺失的服务，不触碰节点仍在运行的服务，也不修改
     * 数据库中的转发状态。这样既能恢复重装节点，又不会中断正常转发。
     */
    private void syncMissingForwards(GostConfigDto gostConfig, Node node) {
        Set<String> serviceNames = getConfigNames(gostConfig.getServices());
        Set<String> chainNames = getConfigNames(gostConfig.getChains());
        List<Tunnel> ingressTunnels = getIngressTunnels(node.getId());
        List<Tunnel> egressTunnels = tunnelService.list(new QueryWrapper<Tunnel>()
                .eq("out_node_id", node.getId()).eq("type", 2));
        Map<Long, Tunnel> relevantTunnels = new LinkedHashMap<>();
        Set<Long> ingressTunnelIds = new HashSet<>();
        for (Tunnel tunnel : ingressTunnels) {
            relevantTunnels.put(tunnel.getId(), tunnel);
            ingressTunnelIds.add(tunnel.getId());
        }
        for (Tunnel tunnel : egressTunnels) {
            relevantTunnels.put(tunnel.getId(), tunnel);
        }
        if (relevantTunnels.isEmpty()) return;

        List<Forward> activeForwards = forwardService.list(new QueryWrapper<Forward>()
                .eq("status", 1).in("tunnel_id", relevantTunnels.keySet()));
        if (activeForwards == null || activeForwards.isEmpty()) return;

        List<UserTunnel> userTunnels = userTunnelService.list(new QueryWrapper<UserTunnel>()
                .in("tunnel_id", relevantTunnels.keySet()));
        Map<String, UserTunnel> userTunnelByOwnerAndTunnel = new LinkedHashMap<>();
        for (UserTunnel userTunnel : userTunnels) {
            userTunnelByOwnerAndTunnel.put(userTunnelKey(userTunnel.getUserId(), userTunnel.getTunnelId()), userTunnel);
        }

        Set<Integer> ownerIds = new HashSet<>();
        for (Forward forward : activeForwards) {
            if (forward.getUserId() != null) {
                ownerIds.add(forward.getUserId());
            }
        }
        Map<Integer, User> ownersById = new LinkedHashMap<>();
        if (!ownerIds.isEmpty()) {
            for (User owner : userService.list(new QueryWrapper<User>().in("id", ownerIds))) {
                ownersById.put(owner.getId().intValue(), owner);
            }
        }
        long now = System.currentTimeMillis();

        for (Forward forward : activeForwards) {
            Tunnel tunnel = relevantTunnels.get(forward.getTunnelId().longValue());
            if (tunnel == null || !Objects.equals(tunnel.getStatus(), 1)) continue;

            boolean isInNode = ingressTunnelIds.contains(tunnel.getId());
            boolean isOutNode = Objects.equals(tunnel.getOutNodeId(), node.getId());
            UserTunnel userTunnel = userTunnelByOwnerAndTunnel.get(userTunnelKey(forward.getUserId(), forward.getTunnelId()));
            User owner = ownersById.get(forward.getUserId());
            if (!canRestoreForward(owner, userTunnel, now)) {
                continue;
            }
            int userTunnelId = userTunnel == null ? 0 : userTunnel.getId();
            String serviceName = forward.getId() + "_" + forward.getUserId() + "_" + userTunnelId;

            boolean mainServiceMissing = isInNode
                    && !serviceNames.contains(serviceName + "_tcp")
                    && !serviceNames.contains(serviceName + "_udp");
            boolean chainMissing = isInNode && Objects.equals(tunnel.getType(), 2)
                    && !chainNames.contains(serviceName + "_chains");
            boolean remoteServiceMissing = isOutNode && Objects.equals(tunnel.getType(), 2)
                    && !serviceNames.contains(serviceName + "_tls");

            if (mainServiceMissing || chainMissing || remoteServiceMissing) {
                safeExecute(() -> restoreMissingForward(node, forward, tunnel, userTunnel, serviceName,
                        mainServiceMissing, chainMissing, remoteServiceMissing), "恢复缺失转发 " + serviceName);
            }
        }
    }

    private String userTunnelKey(Integer userId, Integer tunnelId) {
        return userId + ":" + tunnelId;
    }

    /**
     * Batch-load inventory references so a large node report does not issue a
     * database query for every service or chain it contains.
     */
    private Map<Long, Forward> loadForwardsByConfigItems(List<ConfigItem> configItems) {
        Set<Long> forwardIds = new HashSet<>();
        for (ConfigItem configItem : configItems) {
            if (configItem == null) {
                continue;
            }
            String[] parts = parseServiceName(configItem.getName());
            if (parts.length == 4) {
                Long forwardId = parseForwardId(parts[0]);
                if (forwardId != null) {
                    forwardIds.add(forwardId);
                }
            }
        }
        Map<Long, Forward> forwardsById = new LinkedHashMap<>();
        if (!forwardIds.isEmpty()) {
            for (Forward forward : forwardService.listByIds(forwardIds)) {
                forwardsById.put(forward.getId(), forward);
            }
        }
        return forwardsById;
    }

    private Long parseForwardId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Do not resurrect an entitlement that is disabled, expired, or exhausted
     * simply because one of its nodes has reconnected. All checks are made
     * from the two batch-loaded maps above, avoiding a per-forward SQL query.
     */
    private boolean canRestoreForward(User owner, UserTunnel userTunnel, long now) {
        if (owner == null || !Objects.equals(owner.getStatus(), 1)
                || (owner.getExpTime() != null && owner.getExpTime() <= now)) {
            return false;
        }
        if (Objects.equals(owner.getRoleId(), 0)) {
            return true;
        }
        return hasRemainingTraffic(owner.getFlow(), owner.getInFlow(), owner.getOutFlow())
                && userTunnel != null && Objects.equals(userTunnel.getStatus(), 1)
                && (userTunnel.getExpTime() == null || userTunnel.getExpTime() > now)
                && hasRemainingTraffic(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow());
    }

    private boolean hasRemainingTraffic(Long quotaGb, Long inbound, Long outbound) {
        if (quotaGb == null || quotaGb <= 0) {
            return false;
        }
        try {
            return Math.addExact(inbound == null ? 0 : inbound, outbound == null ? 0 : outbound)
                    < Math.multiplyExact(quotaGb, 1024L * 1024 * 1024L);
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private Set<String> getConfigNames(List<ConfigItem> configItems) {
        Set<String> names = new HashSet<>();
        if (configItems == null) return names;
        for (ConfigItem configItem : configItems) {
            if (configItem != null && configItem.getName() != null) {
                names.add(configItem.getName());
            }
        }
        return names;
    }

    private List<Tunnel> getIngressTunnels(Long nodeId) {
        Set<Long> tunnelIds = new HashSet<>();
        tunnelEntryNodeMapper.selectList(new QueryWrapper<TunnelEntryNode>().eq("node_id", nodeId))
                .forEach(entry -> tunnelIds.add(entry.getTunnelId()));
        // Fallback preserves recovery for a legacy installation before its first
        // migration transaction has completed.
        tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", nodeId))
                .forEach(tunnel -> tunnelIds.add(tunnel.getId()));
        return tunnelIds.isEmpty() ? new ArrayList<>() : tunnelService.listByIds(tunnelIds);
    }

    private void restoreMissingForward(Node node, Forward forward, Tunnel tunnel, UserTunnel userTunnel,
                                       String serviceName, boolean mainServiceMissing, boolean chainMissing,
                                       boolean remoteServiceMissing) {
        boolean ingress = mainServiceMissing || chainMissing;
        String cleanupError = userTunnelAliasService.removeLegacyOnNode(node.getId(), forward, tunnel, userTunnel, ingress);
        if (cleanupError == null && ingress && remoteServiceMissing) {
            cleanupError = userTunnelAliasService.removeLegacyOnNode(node.getId(), forward, tunnel, userTunnel, false);
        }
        if (cleanupError != null) {
            log.warn("节点 {} 的转发 {} 暂缓恢复：{}", node.getId(), forward.getId(), cleanupError);
            return;
        }
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();

        if (chainMissing) {
            String remoteAddress = formatAddress(tunnel.getOutIp(), forward.getOutPort());
            GostDto result = GostUtil.AddChains(node.getId(), serviceName, remoteAddress,
                    tunnel.getProtocol(), tunnel.getInterfaceName());
            logRestoreResult("链", serviceName, node.getId(), result);
        }

        if (remoteServiceMissing) {
            GostDto result = GostUtil.AddRemoteService(node.getId(), serviceName, forward.getOutPort(),
                    forward.getRemoteAddr(), tunnel.getProtocol(), forward.getStrategy(), tunnel.getInterfaceName());
            logRestoreResult("远端服务", serviceName, node.getId(), result);
        }

        if (mainServiceMissing) {
            String interfaceName = Objects.equals(tunnel.getType(), 2) ? null : forward.getInterfaceName();
            GostDto result = GostUtil.AddService(node.getId(), serviceName, forward.getInPort(), limiter,
                    forward.getRemoteAddr(), tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
            logRestoreResult("主服务", serviceName, node.getId(), result);
        }
    }

    private String formatAddress(String host, Integer port) {
        return host != null && host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private void logRestoreResult(String configType, String serviceName, Long nodeId, GostDto result) {
        if (result == null || !Objects.equals(result.getMsg(), "OK")) {
            log.warn("恢复{}失败: {} (节点: {}): {}", configType, serviceName, nodeId,
                    result == null ? "无响应" : result.getMsg());
        } else {
            log.info("已恢复{}: {} (节点: {})", configType, serviceName, nodeId);
        }
    }

    /**
     * 安全执行操作，捕获异常
     */
    private void safeExecute(Runnable operation, String operationDesc) {
        try {
            operation.run();
        } catch (Exception e) {
            log.info("执行操作失败: {}", operationDesc, e);
        }
    }


    /**
     * 解析服务名称
     */
    private String[] parseServiceName(String serviceName) {
        return serviceName == null ? new String[0] : serviceName.split("_");
    }

    private boolean isDuplicateInventory(Long nodeId, GostConfigDto config) {
        if (nodeId == null) return false;
        long now = System.currentTimeMillis();
        InventoryFingerprint previous = recentInventories.put(nodeId,
                new InventoryFingerprint(configSignature(config.getServices()), configSignature(config.getChains()),
                        configSignature(config.getLimiters()), now));
        return previous != null
                && now - previous.receivedAt < DUPLICATE_INVENTORY_WINDOW_MILLIS
                && previous.sameContents(recentInventories.get(nodeId));
    }

    private String configSignature(List<ConfigItem> items) {
        if (items == null || items.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (ConfigItem item : items) {
            if (item != null && item.getName() != null) names.add(item.getName());
        }
        names.sort(String::compareTo);
        return String.join("\u001f", names);
    }

    private static final class InventoryFingerprint {
        private final String services;
        private final String chains;
        private final String limiters;
        private final long receivedAt;

        private InventoryFingerprint(String services, String chains, String limiters, long receivedAt) {
            this.services = services;
            this.chains = chains;
            this.limiters = limiters;
            this.receivedAt = receivedAt;
        }

        private boolean sameContents(InventoryFingerprint other) {
            return other != null && Objects.equals(services, other.services)
                    && Objects.equals(chains, other.chains) && Objects.equals(limiters, other.limiters);
        }
    }
}

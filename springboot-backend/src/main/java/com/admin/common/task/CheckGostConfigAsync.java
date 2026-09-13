package com.admin.common.task;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class CheckGostConfigAsync {

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
    private TunnelEntryNodeMapper tunnelEntryNodeMapper;



    /**
     * 清理孤立的Gost配置项
     */
    @Async
    public void cleanNodeConfigs(String node_id, GostConfigDto gostConfig) {
        System.out.println(JSONObject.toJSONString(gostConfig));
        Node node = nodeService.getById(node_id);
        if (node != null) {
            cleanOrphanedServices(gostConfig, node);
            cleanOrphanedChains(gostConfig, node);
            cleanOrphanedLimiters(gostConfig, node);
            // 重装节点会让 GOST 侧的运行时服务消失，但数据库中的转发记录仍然存在。
            // 配置上报发生在 WebSocket 建立之后，正是无人工编辑即可恢复缺失服务的安全时机。
            syncMissingForwards(gostConfig, node);
            // 节点重启后内存中的 limiter 会丢失。配置上报正是节点恢复
            // 可控状态的时机，必须在这里把数据库中的限速规则补回去。
            syncLimiters(gostConfig, node);
        }
    }

    /**
     * 清理孤立的服务
     */
    private void cleanOrphanedServices(GostConfigDto gostConfig, Node node) {
        if (gostConfig.getServices() == null) {
            return;
        }

        for (ConfigItem service : gostConfig.getServices()) {
            safeExecute(() -> {

                if (!Objects.equals(service.getName(), "web_api")){
                    String[] serviceIds = parseServiceName(service.getName());
                    if (serviceIds.length == 4) {
                        String forwardId = serviceIds[0];
                        String userId = serviceIds[1];
                        String userTunnelId = serviceIds[2];
                        String type = serviceIds[3];

                        if (Objects.equals(type, "tcp")) { // 只处理TCP，避免重复处理
                            Forward forward = forwardService.getById(forwardId);
                            if (forward == null) {
                                log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                                GostDto gostDto = GostUtil.DeleteService(node.getId(), forwardId + "_" + userId + "_" + userTunnelId);
                                System.out.println(gostDto);
                            }
                        }


                        if (Objects.equals(type, "tls")) {
                            Forward forward = forwardService.getById(forwardId);
                            if (forward == null) {
                                log.info("删除孤立的服务: {} (节点: {})", service.getName(), node.getId());
                                GostUtil.DeleteRemoteService(node.getId(), forwardId+"_"+userId+"_"+userTunnelId);
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
        

        for (ConfigItem chain : gostConfig.getChains()) {
            safeExecute(() -> {
                String[] serviceIds = parseServiceName(chain.getName());
                if (serviceIds.length == 4) {
                    String forwardId = serviceIds[0];
                    String userId = serviceIds[1];
                    String userTunnelId = serviceIds[2];
                    String type = serviceIds[3];
                    
                    if (Objects.equals(type, "chains")) {
                        Forward forward = forwardService.getById(forwardId);
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
        

        for (ConfigItem limiter : gostConfig.getLimiters()) {
            safeExecute(() -> {
                SpeedLimit speedLimit = speedLimitService.getById(limiter.getName());
                if (speedLimit == null) {
                    log.info("删除孤立的限流器: {} (节点: {})", limiter.getName(), node.getId());
                    GostUtil.DeleteLimiters(node.getId(), Long.parseLong(limiter.getName()));
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
                List<Long> limiters_ids = new ArrayList<>();
                List<Long>  speedLimits_ids = new ArrayList<>();
                if (limiters != null){
                    for (ConfigItem limiter : limiters) {
                        limiters_ids.add(Long.valueOf(limiter.getName()));
                    }
                }
                for (SpeedLimit speedLimit : speedLimits) {
                    speedLimits_ids.add(speedLimit.getId());
                }
                List<Long> diff = new ArrayList<>(speedLimits_ids);
                diff.removeAll(limiters_ids);
                System.out.println(diff);
                if (!diff.isEmpty()) {

                    for (Long speed_id : diff) {
                        SpeedLimit speedLimit = speedLimitService.getById(speed_id);
                        if (speedLimit != null) {
                            SpeedLimitUpdateDto speedLimitUpdateDto = new SpeedLimitUpdateDto();
                            speedLimitUpdateDto.setId(speed_id);
                            speedLimitUpdateDto.setName(speedLimit.getName());
                            speedLimitUpdateDto.setSpeed(speedLimit.getSpeed());
                            speedLimitUpdateDto.setTunnelId(speedLimit.getTunnelId());
                            speedLimitUpdateDto.setTunnelName(speedLimit.getTunnelName());
                            speedLimitService.updateSpeedLimit(speedLimitUpdateDto);
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
        List<Forward> activeForwards = forwardService.list(new QueryWrapper<Forward>().eq("status", 1));
        if (activeForwards == null || activeForwards.isEmpty()) return;

        for (Forward forward : activeForwards) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null || !Objects.equals(tunnel.getStatus(), 1)) continue;

            boolean isInNode = isIngressNode(tunnel.getId(), tunnel.getInNodeId(), node.getId());
            boolean isOutNode = Objects.equals(tunnel.getOutNodeId(), node.getId());
            if (!isInNode && !isOutNode) continue;

            UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", forward.getUserId())
                    .eq("tunnel_id", forward.getTunnelId()));
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

    private boolean isIngressNode(Long tunnelId, Long legacyInNodeId, Long nodeId) {
        if (Objects.equals(legacyInNodeId, nodeId)) {
            return true;
        }
        return tunnelEntryNodeMapper.selectCount(new QueryWrapper<TunnelEntryNode>()
                .eq("tunnel_id", tunnelId).eq("node_id", nodeId)) > 0;
    }

    private void restoreMissingForward(Node node, Forward forward, Tunnel tunnel, UserTunnel userTunnel,
                                       String serviceName, boolean mainServiceMissing, boolean chainMissing,
                                       boolean remoteServiceMissing) {
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
        return serviceName.split("_");
    }
}

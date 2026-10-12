package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostDto;
import com.admin.common.service.UserProtocolPolicyService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.*;
import com.admin.service.*;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The grant is the durable desired state; failed/offline ACKs remain pending. */
@Service
public class UserProtocolPolicySyncService {
    @Resource @Lazy private UserTunnelService userTunnelService;
    @Resource @Lazy private ForwardService forwardService;
    @Resource @Lazy private TunnelService tunnelService;
    @Resource private UserProtocolPolicyService policyService;
    @Resource private TunnelIngressNodeResolver ingressNodeResolver;
    @Resource @Lazy private UserProtocolPolicySyncService self;
    private final Set<Integer> running = ConcurrentHashMap.newKeySet();
    private int retryCursor = 0;

    @Scheduled(fixedDelay = 15_000L)
    public void retryPending() {
        List<UserTunnel> pending = userTunnelService.list(new QueryWrapper<UserTunnel>()
                .eq("protocol_sync_pending", true).gt("id", retryCursor).orderByAsc("id").last("LIMIT 32"));
        if (pending.isEmpty()) { retryCursor = 0; return; }
        for (UserTunnel grant : pending) {
            retryCursor = grant.getId();
            try { self.syncGrant(grant.getId()); }
            catch (org.springframework.core.task.TaskRejectedException ignored) { /* Durable pending flag retries later. */ }
        }
    }

    @Async("protocolPolicyExecutor")
    public void syncGrant(Integer id) {
        if (id == null || !running.add(id)) return;
        UserTunnel grant = null;
        String failure = null;
        try {
            grant = userTunnelService.getById(id);
            if (grant == null) return;
            Tunnel tunnel = tunnelService.getById(grant.getTunnelId());
            User owner = policyService.owner(grant.getUserId());
            if (tunnel == null || owner == null) { failure = "用户或隧道不存在"; return; }
            JSONObject policy = UserProtocolPolicyService.policy(owner, grant);
            List<Forward> forwards = forwardService.list(new QueryWrapper<Forward>()
                    .eq("user_id", grant.getUserId()).eq("tunnel_id", grant.getTunnelId()));
            List<String> tcpNames = new ArrayList<>();
            List<String> udpNames = new ArrayList<>();
            for (Forward forward : forwards) {
                if (Objects.equals(forward.getStatus(), com.admin.common.constants.ForwardStatus.DELETING)) continue;
                String base = forward.getId() + "_" + forward.getUserId() + "_" + grant.getId();
                tcpNames.add(base + "_tcp"); udpNames.add(base + "_udp");
            }
            Set<Long> nodes = ingressNodeResolver.resolveNodeIds(tunnel);
            if (!tcpNames.isEmpty() && nodes.isEmpty()) failure = "隧道没有入口节点";
            for (Long node : nodes) {
                // A missing UDP service must never prevent an existing TCP
                // service from receiving a tightened policy (or vice versa).
                String tcpFailure = dispatchPolicyBatches(node, tcpNames, policy);
                String udpFailure = dispatchPolicyBatches(node, udpNames, policy);
                if (tcpFailure != null) failure = tcpFailure;
                if (udpFailure != null) failure = udpFailure;
            }
        } catch (Exception exception) { failure = "协议限制同步失败，请检查节点连接及后端版本"; }
        finally {
            try {
                if (grant != null) recordResult(grant, failure);
            } finally { running.remove(id); }
        }
    }

    private void recordResult(UserTunnel grant, String failure) {
        UpdateWrapper<UserTunnel> update = new UpdateWrapper<UserTunnel>().eq("id", grant.getId());
        if (grant.getProtocolSyncGeneration() == null) update.isNull("protocol_sync_generation");
        else update.eq("protocol_sync_generation", grant.getProtocolSyncGeneration());
        update.set("protocol_sync_pending", failure != null)
                .set("protocol_sync_error", failure == null ? null : failure.substring(0, Math.min(255, failure.length())));
        userTunnelService.update(null, update);
    }

    /**
     * Apply to all present services even when one requested service disappeared.
     * The Go command validates a complete batch before changing anything. Only
     * its exact error for a requested missing name permits removing that name
     * and retrying the remainder; offline, unsupported, timeout and persistence
     * errors retain the durable pending flag. Missing services are recovered by
     * the inventory task, whose create command already carries the latest policy.
     */
    private String dispatchPolicyBatches(Long nodeId, Collection<String> requested, JSONObject policy) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(requested));
        String failure = null;
        for (int start = 0; start < names.size(); start += 128) {
            List<String> remaining = new ArrayList<>(names.subList(start, Math.min(start + 128, names.size())));
            while (!remaining.isEmpty()) {
                GostDto result;
                try {
                    result = GostUtil.SetUserProtocolPolicy(nodeId, List.copyOf(remaining), policy);
                } catch (RuntimeException exception) {
                    failure = "入口节点 " + nodeId + " 协议限制请求失败，请检查连接及节点后端版本";
                    break;
                }
                if (result != null && "OK".equals(result.getMsg())) break;
                String message = result == null ? null : result.getMsg();
                String absentName = null;
                if (message != null) {
                    for (String name : remaining) {
                        if (message.equals("service " + name + " not found")) {
                            absentName = name;
                            break;
                        }
                    }
                }
                if (absentName != null) {
                    remaining.remove(absentName);
                    continue;
                }
                failure = "入口节点 " + nodeId + " 未确认：" + (message == null ? "无响应" : message)
                        + "；请检查连接及节点后端版本";
                break;
            }
        }
        return failure;
    }

    /** Reconcile actual ingress names, including historical grant aliases. */
    public void syncInventory(Long nodeId, List<ConfigItem> inventory) {
        if (inventory == null) return;
        Map<String, Set<String>> groups = new LinkedHashMap<>();
        Map<String, JSONObject> policies = new HashMap<>();
        Map<String, UserTunnel> grants = new HashMap<>();
        Map<Integer, Tunnel> tunnels = new HashMap<>();
        Map<Integer, Set<Long>> ingress = new HashMap<>();
        for (ConfigItem item : inventory) {
            if (item == null || item.getName() == null ||
                    (!item.getName().endsWith("_tcp") && !item.getName().endsWith("_udp"))) continue;
            String[] parts = item.getName().split("_");
            if (parts.length != 4) continue;
            try {
                Forward forward = forwardService.getById(Long.valueOf(parts[0]));
                if (forward == null || !Objects.equals(forward.getUserId(), Integer.valueOf(parts[1]))) continue;
                Tunnel tunnel = tunnels.computeIfAbsent(forward.getTunnelId(), id -> tunnelService.getById(id));
                if (tunnel == null || !ingress.computeIfAbsent(forward.getTunnelId(), id -> ingressNodeResolver.resolveNodeIds(tunnel)).contains(nodeId)) continue;
                String key = forward.getUserId() + ":" + forward.getTunnelId();
                if (!policies.containsKey(key)) {
                    UserTunnel grant = policyService.grant(forward);
                    policies.put(key, policyService.forForward(forward));
                    grants.put(key, grant);
                }
                // Inventory may contain just one protocol or a historical grant
                // alias. Dispatch only actual names, without inventing a peer.
                groups.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(item.getName());
            } catch (IllegalArgumentException | IllegalStateException ignored) { /* Non-panel/deleted-owner service. */ }
        }
        groups.forEach((key, names) -> {
            String failure = dispatchPolicyBatches(nodeId, names, policies.get(key));
            UserTunnel grant = grants.get(key);
            // A single-node ACK cannot clear the pending status of a grant
            // published on multiple ingress nodes; the full worker does that.
            if (failure != null && grant != null) recordResult(grant, failure);
            if (grant != null && Boolean.TRUE.equals(grant.getProtocolSyncPending())) self.syncGrant(grant.getId());
        });
    }
}

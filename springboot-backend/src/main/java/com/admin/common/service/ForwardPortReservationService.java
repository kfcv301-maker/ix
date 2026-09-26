package com.admin.common.service;

import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPortReservation;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardPortReservationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns durable node-port claims for a forward.  A unique database key makes a
 * competing request lose atomically even when both requests selected the same
 * free port from a stale availability read.
 */
@Service
public class ForwardPortReservationService {

    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;

    @Resource
    private ForwardPortReservationMapper forwardPortReservationMapper;

    @Resource
    private TunnelIngressNodeResolver ingressNodeResolver;

    public ReservationLease reserve(Forward forward, Tunnel tunnel) {
        if (forward == null || forward.getId() == null || tunnel == null) {
            return ReservationLease.failed("无法确定端口保留信息");
        }
        List<ReservationTarget> desired = desiredTargets(forward, tunnel);
        if (desired.isEmpty()) {
            return ReservationLease.failed("隧道没有可用的入口节点");
        }

        long now = System.currentTimeMillis();
        List<ForwardPortReservation> inserted = new ArrayList<>();
        for (ReservationTarget target : desired) {
            int insertedRows = forwardPortReservationMapper.insertIgnore(target.nodeId, target.port,
                    forward.getId(), target.endpoint, now);
            ForwardPortReservation owner = forwardPortReservationMapper.selectByNodeAndPort(target.nodeId, target.port);
            if (owner == null || !Objects.equals(owner.getForwardId(), forward.getId())) {
                rollbackInserted(forward.getId(), inserted);
                return ReservationLease.failed("端口 " + target.port + " 已被节点 " + target.nodeId + " 上的其他转发占用");
            }
            if (insertedRows == 1) {
                inserted.add(owner);
            }
        }
        return ReservationLease.success(forward.getId(), desired, inserted);
    }

    /** Commit the desired layout only after the Forward row and GOST update succeed. */
    public void commit(ReservationLease lease) {
        if (lease == null || !lease.success) return;
        Set<String> desiredKeys = new HashSet<>();
        for (ReservationTarget target : lease.desired) desiredKeys.add(target.key());
        List<ForwardPortReservation> existing = forwardPortReservationMapper.selectList(
                new QueryWrapper<ForwardPortReservation>().eq("forward_id", lease.forwardId));
        for (ForwardPortReservation item : existing) {
            if (!desiredKeys.contains(key(item.getNodeId(), item.getPort()))) {
                forwardPortReservationMapper.deleteOwnedReservation(item.getId(), lease.forwardId);
            }
        }
    }

    /** Remove only claims created by this failed create/update attempt. */
    public void rollback(ReservationLease lease) {
        if (lease == null || !lease.success) return;
        rollbackInserted(lease.forwardId, lease.inserted);
    }

    public void releaseAll(Long forwardId) {
        if (forwardId != null) forwardPortReservationMapper.deleteByForwardId(forwardId);
    }

    /** A port-only indexed lookup for allocation; it never loads full forwards. */
    public Set<Integer> reservedPorts(Long nodeId, Long excludeForwardId) {
        if (nodeId == null) return Collections.emptySet();
        List<Integer> ports = excludeForwardId == null
                ? forwardPortReservationMapper.selectPortsForNode(nodeId)
                : forwardPortReservationMapper.selectPortsForNodeExcept(nodeId, excludeForwardId);
        return ports == null ? Collections.emptySet() : new LinkedHashSet<>(ports);
    }

    private List<ReservationTarget> desiredTargets(Forward forward, Tunnel tunnel) {
        if (forward.getInPort() == null) return Collections.emptyList();
        Map<String, ReservationTarget> targets = new LinkedHashMap<>();
        for (Long nodeId : ingressNodeResolver.resolveNodeIds(tunnel)) {
            if (nodeId != null) {
                ReservationTarget target = new ReservationTarget(nodeId, forward.getInPort(), "ingress");
                targets.put(target.key(), target);
            }
        }
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)
                && tunnel.getOutNodeId() != null && forward.getOutPort() != null) {
            ReservationTarget target = new ReservationTarget(tunnel.getOutNodeId(), forward.getOutPort(), "egress");
            targets.put(target.key(), target);
        }
        return new ArrayList<>(targets.values());
    }

    private void rollbackInserted(Long forwardId, Collection<ForwardPortReservation> inserted) {
        for (ForwardPortReservation item : inserted) {
            if (item != null && item.getId() != null) {
                forwardPortReservationMapper.deleteOwnedReservation(item.getId(), forwardId);
            }
        }
    }

    private static String key(Long nodeId, Integer port) {
        return String.valueOf(nodeId) + ':' + String.valueOf(port);
    }

    private static final class ReservationTarget {
        private final Long nodeId;
        private final Integer port;
        private final String endpoint;

        private ReservationTarget(Long nodeId, Integer port, String endpoint) {
            this.nodeId = nodeId;
            this.port = port;
            this.endpoint = endpoint;
        }

        private String key() {
            return ForwardPortReservationService.key(nodeId, port);
        }
    }

    public static final class ReservationLease {
        private final boolean success;
        private final String message;
        private final Long forwardId;
        private final List<ReservationTarget> desired;
        private final List<ForwardPortReservation> inserted;

        private ReservationLease(boolean success, String message, Long forwardId,
                                 List<ReservationTarget> desired, List<ForwardPortReservation> inserted) {
            this.success = success;
            this.message = message;
            this.forwardId = forwardId;
            this.desired = desired;
            this.inserted = inserted;
        }

        private static ReservationLease success(Long forwardId, List<ReservationTarget> desired,
                                                List<ForwardPortReservation> inserted) {
            return new ReservationLease(true, null, forwardId, desired, inserted);
        }

        private static ReservationLease failed(String message) {
            return new ReservationLease(false, message, null, Collections.emptyList(), Collections.emptyList());
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }
}

package com.admin.common.utils;

import com.admin.entity.Tunnel;
import com.admin.entity.TunnelEntryNode;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves every ingress that publishes a tunnel.  The relation table is the
 * source of truth for multi-ingress tunnels; {@code in_node_id} remains a
 * compatibility fallback for installations that have not yet been backfilled.
 */
@Component
public class TunnelIngressNodeResolver {

    @Resource
    private TunnelEntryNodeMapper tunnelEntryNodeMapper;

    public Set<Long> resolveNodeIds(Tunnel tunnel) {
        Set<Long> nodeIds = new LinkedHashSet<>();
        if (tunnel == null) {
            return nodeIds;
        }
        if (tunnel.getId() != null) {
            List<TunnelEntryNode> entries = tunnelEntryNodeMapper.selectList(
                    new QueryWrapper<TunnelEntryNode>().eq("tunnel_id", tunnel.getId()).orderByAsc("id"));
            for (TunnelEntryNode entry : entries) {
                if (entry.getNodeId() != null) {
                    nodeIds.add(entry.getNodeId());
                }
            }
        }
        if (nodeIds.isEmpty() && tunnel.getInNodeId() != null) {
            nodeIds.add(tunnel.getInNodeId());
        }
        return nodeIds;
    }

    public boolean isIngressNode(Tunnel tunnel, Long nodeId) {
        return nodeId != null && resolveNodeIds(tunnel).contains(nodeId);
    }
}

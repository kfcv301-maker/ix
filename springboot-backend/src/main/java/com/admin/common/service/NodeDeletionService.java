package com.admin.common.service;

import com.admin.common.lang.R;
import com.admin.common.task.ForwardSyncTaskService;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class NodeDeletionService {
    @Resource private NodeDeletionMapper deletionMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private TunnelMapper tunnelMapper;
    @Resource private ForwardMapper forwardMapper;
    @Resource private TunnelEntryNodeMapper entryNodeMapper;
    @Resource private TunnelEntryDomainMapper entryDomainMapper;
    @Resource private UserTunnelMapper grantMapper;
    @Resource private SpeedLimitMapper speedLimitMapper;
    @Resource private ForwardSyncTaskService syncTasks;

    /** Database deletion commits independently of any node's availability. */
    @Transactional(rollbackFor = Exception.class)
    public R deleteNode(Long id) {
        Node node = deletionMapper.lockNode(id);
        if (node == null) return R.err("节点不存在");
        boolean admin = Objects.equals(JwtUtil.getRoleIdFromToken(), 0);
        Long userId = Long.valueOf(JwtUtil.getUserIdFromToken());
        if (!admin && !Objects.equals(node.getOwnerUserId(), userId)) {
            return R.err(403, "只能删除自己创建的节点");
        }
        List<Tunnel> tunnels = deletionMapper.affectedTunnels(id);
        // Owning one endpoint never grants permission to delete another user's tunnel.
        if (!admin && tunnels.stream().anyMatch(t -> !Objects.equals(t.getOwnerUserId(), userId))) {
            return R.err(403, "关联隧道属于其他用户，请联系管理员删除节点");
        }
        Set<Long> affectedUsers = new LinkedHashSet<>();
        int forwardCount = 0;
        for (Tunnel tunnel : tunnels) {
            List<Forward> forwards = forwardMapper.selectList(new QueryWrapper<Forward>().eq("tunnel_id", tunnel.getId()));
            for (Forward forward : forwards) {
                // Capture all endpoint/alias names before removing grants and tunnel data.
                syncTasks.prepareCascadeDelete(forward, tunnel);
                deletionMapper.deletePauseTasks(forward.getId());
                if (forwardMapper.deleteById(forward.getId()) != 1) {
                    throw new IllegalStateException("删除关联转发失败");
                }
                forwardCount++;
            }
            for (UserTunnel grant : grantMapper.selectList(new QueryWrapper<UserTunnel>().eq("tunnel_id", tunnel.getId()))) {
                if (grant.getUserId() != null) affectedUsers.add(grant.getUserId().longValue());
            }
            deletionMapper.deleteAliases(tunnel.getId());
            grantMapper.delete(new QueryWrapper<UserTunnel>().eq("tunnel_id", tunnel.getId()));
            speedLimitMapper.delete(new QueryWrapper<SpeedLimit>().eq("tunnel_id", tunnel.getId()));
            entryDomainMapper.delete(new QueryWrapper<TunnelEntryDomain>().eq("tunnel_id", tunnel.getId()));
            entryNodeMapper.delete(new QueryWrapper<TunnelEntryNode>().eq("tunnel_id", tunnel.getId()));
            if (tunnelMapper.deleteById(tunnel.getId()) != 1) throw new IllegalStateException("删除关联隧道失败");
        }
        // Also remove stale relations for a tunnel that was already deleted.
        entryNodeMapper.delete(new QueryWrapper<TunnelEntryNode>().eq("node_id", id));
        deletionMapper.deleteNodeReservations(id);
        deletionMapper.deleteNodeCursors(id);
        if (nodeMapper.deleteById(id) != 1) throw new IllegalStateException("节点删除失败");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                WebSocketServer.retireNodeMetrics(id);
                affectedUsers.forEach(WebSocketServer::closeUserSessions);
                syncTasks.closeRetiredNodeIfClean(id);
            }
        });
        return R.ok("节点已删除，同时删除 " + tunnels.size() + " 个隧道、" + forwardCount + " 条转发规则");
    }
}

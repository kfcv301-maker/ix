package com.admin.common.service;

import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeDeletionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.LinkedHashMap;
import com.admin.common.lang.R;
import com.admin.common.constants.ForwardStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Service
public class ForwardCreationService {
    @Resource private NodeDeletionMapper nodeDeletionMapper;
    @Resource private ForwardMapper forwardMapper;
    @Resource private com.admin.mapper.UserMapper userMapper;
    @Resource @org.springframework.context.annotation.Lazy
    private com.admin.common.task.ForwardSyncTaskService syncTasks;
    @Resource private ForwardPortReservationService reservations;
    @Resource private com.admin.mapper.UserTunnelMapper userTunnelMapper;

    /** Return an existing receipt before allocation/quota checks can reject a successful retry. */
    public R findExisting(Integer userId, String requestId, String requestHash) {
        if (requestId == null) return null;
        Forward existing = forwardMapper.selectOne(new QueryWrapper<Forward>()
                .eq("user_id", userId).eq("create_request_id", requestId));
        if (existing == null) return null;
        User owner = userMapper.selectById(userId);
        if (owner == null || !Objects.equals(owner.getStatus(), 1)) return R.err("转发所属用户已禁用");
        return replay(existing, requestHash);
    }

    /** Rule, port claims and durable node tasks commit together. No node RPC runs here. */
    @Transactional
    public R createQueued(Forward forward) {
        User owner = userMapper.lockById(forward.getUserId().longValue());
        if (owner == null || !Objects.equals(owner.getStatus(), 1)) throw new Rejected("转发所属用户已禁用", false);
        if (forward.getCreateRequestId() != null) {
            Forward existing = forwardMapper.selectOne(new QueryWrapper<Forward>()
                    .eq("user_id", forward.getUserId()).eq("create_request_id", forward.getCreateRequestId()));
            if (existing != null) return replay(existing, forward.getCreateRequestHash());
        }
        Tunnel tunnel = nodeDeletionMapper.lockTunnel(forward.getTunnelId().longValue());
        if (tunnel == null || !Objects.equals(tunnel.getStatus(), 1)) throw new Rejected("隧道已删除或禁用", false);
        // Serialize quota checks with inserts, including concurrent requests with different IDs.
        if (!Objects.equals(owner.getRoleId(), 0)) {
            var grant = userTunnelMapper.selectOne(new QueryWrapper<com.admin.entity.UserTunnel>()
                    .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
            if (grant == null) throw new Rejected("你没有该隧道权限", false);
            long total = forwardMapper.selectCount(new QueryWrapper<Forward>().eq("user_id", forward.getUserId()));
            long tunnelTotal = forwardMapper.selectCount(new QueryWrapper<Forward>()
                    .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
            if (owner.getNum() == null || total >= owner.getNum()) throw new Rejected("用户总转发数量已达上限", false);
            if (grant.getNum() == null || tunnelTotal >= grant.getNum()) throw new Rejected("该隧道转发数量已达上限", false);
        }
        forward.setStatus(ForwardStatus.PAUSED);
        if (forwardMapper.insert(forward) != 1) throw new Rejected("保存转发失败", false);
        var lease = reservations.reserve(forward, tunnel);
        if (!lease.isSuccess()) throw new Rejected(lease.getMessage(), true);
        R queued = syncTasks.requestResume(forward.getId());
        if (queued.getCode() != 0) throw new Rejected(queued.getMsg(), false);
        forward.setStatus(ForwardStatus.SYNCING);
        return receipt(forward, "规则已保存，正在同步到节点，请勿重复添加");
    }

    private R replay(Forward existing, String requestHash) {
        if (!Objects.equals(existing.getCreateRequestHash(), requestHash)) {
            return R.err("该创建请求已用于其他内容，请重新打开表单后提交");
        }
        return receipt(existing, Objects.equals(existing.getStatus(), ForwardStatus.ACTIVE)
                ? "该请求已创建成功，已返回原规则" : "该请求已保存，已返回原规则及当前同步状态");
    }

    private R receipt(Forward forward, String message) {
        var data = new LinkedHashMap<String, Object>();
        data.put("forwardId", forward.getId());
        data.put("inPort", forward.getInPort());
        data.put("status", forward.getStatus());
        R result = R.ok(); result.setMsg(message); result.setData(data); return result;
    }

    public static class Rejected extends RuntimeException {
        private final boolean portConflict;
        public Rejected(String message, boolean portConflict) { super(message); this.portConflict = portConflict; }
        public boolean isPortConflict() { return portConflict; }
    }

    /** Commit before Agent commands so inventory reconciliation can see the new rule. */
    @Transactional
    public boolean save(Forward forward) {
        User owner = userMapper.lockById(forward.getUserId().longValue());
        if (owner == null || !Objects.equals(owner.getStatus(), 1)) return false;
        Tunnel tunnel = nodeDeletionMapper.lockTunnel(forward.getTunnelId().longValue());
        if (tunnel == null || !Objects.equals(tunnel.getStatus(), 1)) return false;
        return forwardMapper.insert(forward) == 1;
    }

    /** A late edit must not override a pause committed by account disable. */
    @Transactional
    public boolean saveUpdated(Forward forward) {
        User owner = userMapper.lockById(forward.getUserId().longValue());
        if (forwardMapper.updateById(forward) != 1) return false;
        pauseIfDisabled(owner, forward.getId());
        return true;
    }

    /** Recheck after Agent creation, which can finish after a disable's pause ACK. */
    @Transactional
    public void enforceOwnerStatus(Forward forward) {
        User owner = userMapper.lockById(forward.getUserId().longValue());
        if (forwardMapper.selectById(forward.getId()) != null) pauseIfDisabled(owner, forward.getId());
    }

    private void pauseIfDisabled(User owner, Long forwardId) {
        if (owner == null || !Objects.equals(owner.getStatus(), 1)) {
            var result = syncTasks.queueSafetyPause(forwardId);
            if (result.getCode() != 0) throw new IllegalStateException(result.getMsg());
        }
    }
}

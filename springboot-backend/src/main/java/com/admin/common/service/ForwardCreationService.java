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

@Service
public class ForwardCreationService {
    @Resource private NodeDeletionMapper nodeDeletionMapper;
    @Resource private ForwardMapper forwardMapper;
    @Resource private com.admin.mapper.UserMapper userMapper;
    @Resource @org.springframework.context.annotation.Lazy
    private com.admin.common.task.ForwardSyncTaskService syncTasks;

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

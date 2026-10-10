package com.admin.common.service;

import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
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

    /** Commit before Agent commands so inventory reconciliation can see the new rule. */
    @Transactional
    public boolean save(Forward forward) {
        Tunnel tunnel = nodeDeletionMapper.lockTunnel(forward.getTunnelId().longValue());
        if (tunnel == null || !Objects.equals(tunnel.getStatus(), 1)) return false;
        return forwardMapper.insert(forward) == 1;
    }
}

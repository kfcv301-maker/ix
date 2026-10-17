package com.admin.common.service;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.UserTunnelAliasMapper;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Old names remain billable until node ACKs let them be replaced safely. */
@Service
public class UserTunnelAliasService {
    @Resource private UserTunnelAliasMapper aliasMapper;

    public List<String> legacyNames(Forward forward, UserTunnel grant) {
        if (grant == null || !matchesForward(forward, grant)) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Integer id : aliasMapper.selectAliases(grant.getId(), forward.getUserId(), forward.getTunnelId())) {
            if (id == null || id <= 0 || Objects.equals(id, grant.getId())) continue;
            names.add(forward.getId() + "_" + forward.getUserId() + "_" + id);
        }
        return new ArrayList<>(names);
    }

    public String removeLegacyOnNode(Long nodeId, Forward forward, Tunnel tunnel, UserTunnel grant, boolean ingress) {
        if (grant != null && !matchesForward(forward, grant)) return "历史授权归属与转发不一致，暂缓清理";
        for (String name : legacyNames(forward, grant)) {
            if (ingress) {
                // Send singleton batches for compatibility with Agents whose
                // delete preflight rejects both protocols if either is absent.
                for (String protocol : List.of("tcp", "udp")) {
                    GostDto result = GostUtil.DeleteServiceProtocol(nodeId, name, protocol);
                    if (!absentOrSuccess(result, "service", name + "_" + protocol)) {
                        return "历史授权服务清理尚未获节点确认";
                    }
                }
            } else if (!absentOrSuccess(GostUtil.DeleteRemoteService(nodeId, name), "service", name + "_tls")) {
                return "历史授权服务清理尚未获节点确认";
            }
            if (ingress && Integer.valueOf(2).equals(tunnel.getType())) {
                if (!absentOrSuccess(GostUtil.DeleteChains(nodeId, name), "chain", name + "_chains")) {
                    return "历史授权链清理尚未获节点确认";
                }
            }
        }
        return null;
    }

    private boolean matchesForward(Forward forward, UserTunnel grant) {
        return forward != null && forward.getId() != null && forward.getId() > 0
                && grant.getId() != null && grant.getId() > 0
                && forward.getUserId() != null && forward.getTunnelId() != null
                && Objects.equals(forward.getUserId(), grant.getUserId())
                && Objects.equals(forward.getTunnelId(), grant.getTunnelId());
    }

    private boolean absentOrSuccess(GostDto result, String kind, String name) {
        if (result == null || result.getMsg() == null) return false;
        String message = result.getMsg().trim();
        return "OK".equalsIgnoreCase(message) || (kind + " " + name + " not found").equals(message);
    }
}

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
import java.util.List;
import java.util.Locale;

/** Old names remain billable until node ACKs let them be replaced safely. */
@Service
public class UserTunnelAliasService {
    @Resource private UserTunnelAliasMapper aliasMapper;

    public List<String> legacyNames(Forward forward, UserTunnel grant) {
        if (grant == null) return List.of();
        List<String> names = new ArrayList<>();
        for (Integer id : aliasMapper.selectAliases(grant.getId(), forward.getUserId(), forward.getTunnelId())) {
            names.add(forward.getId() + "_" + forward.getUserId() + "_" + id);
        }
        return names;
    }

    public String removeLegacyOnNode(Long nodeId, Forward forward, Tunnel tunnel, UserTunnel grant, boolean ingress) {
        for (String name : legacyNames(forward, grant)) {
            GostDto result = ingress ? GostUtil.DeleteService(nodeId, name) : GostUtil.DeleteRemoteService(nodeId, name);
            if (!absentOrSuccess(result)) return "历史授权服务清理尚未获节点确认";
            if (ingress && Integer.valueOf(2).equals(tunnel.getType())) {
                if (!absentOrSuccess(GostUtil.DeleteChains(nodeId, name))) return "历史授权链清理尚未获节点确认";
            }
        }
        return null;
    }

    private boolean absentOrSuccess(GostDto result) {
        return result != null && result.getMsg() != null && ("OK".equalsIgnoreCase(result.getMsg())
                || result.getMsg().toLowerCase(Locale.ROOT).contains("not found"));
    }
}

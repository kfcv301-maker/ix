package com.admin.common.service;

import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;

@Service
public class UserProtocolPolicyService {
    public static final String UNRESTRICTED = "UNRESTRICTED";
    public static final String TLS_ONLY = "TLS_ONLY";
    public static final String TLS_DENY = "TLS_DENY";
    private static final Set<String> MODES = Set.of(UNRESTRICTED, TLS_ONLY, TLS_DENY);
    @Resource @Lazy private UserService userService;
    @Resource @Lazy private UserTunnelService userTunnelService;

    public static String normalize(String mode) {
        if (mode == null) return UNRESTRICTED;
        if (!MODES.contains(mode)) throw new IllegalArgumentException("TLS限制只能选择不限制、只允许TLS或禁止TLS");
        return mode;
    }

    public User owner(Integer userId) { return userService.getById(userId); }

    public UserTunnel grant(Forward forward) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()));
    }

    public JSONObject forForward(Forward forward) {
        User owner = userService.getById(forward.getUserId());
        if (owner == null) throw new IllegalStateException("转发归属用户不存在");
        return policy(owner, Objects.equals(owner.getRoleId(), 0) ? null : grant(forward));
    }

    public static JSONObject policy(User owner, UserTunnel grant) {
        boolean admin = owner != null && Objects.equals(owner.getRoleId(), 0);
        JSONObject data = new JSONObject();
        data.put("tls", admin || grant == null ? UNRESTRICTED : normalize(grant.getTlsPolicy()));
        data.put("allowUdp", admin || grant == null || !Boolean.FALSE.equals(grant.getAllowUdp()));
        data.put("revision", admin || grant == null || grant.getProtocolPolicyRevision() == null ? 0L : grant.getProtocolPolicyRevision());
        data.put("administrator", admin);
        return data;
    }

    public static boolean restricted(JSONObject policy) {
        return policy != null && (!UNRESTRICTED.equals(policy.getString("tls")) || !policy.getBooleanValue("allowUdp"));
    }

    public void apply(UserTunnel grant, String tls, Boolean udp, boolean update) {
        User owner = owner(grant.getUserId());
        if (owner == null) throw new IllegalArgumentException("用户不存在");
        String mode = normalize(update && tls == null ? grant.getTlsPolicy() : tls);
        boolean allowUdp = udp == null ? !update || !Boolean.FALSE.equals(grant.getAllowUdp()) : udp;
        if (Objects.equals(owner.getRoleId(), 0)) { mode = UNRESTRICTED; allowUdp = true; }
        grant.setTlsPolicy(mode);
        grant.setAllowUdp(allowUdp);
    }
}

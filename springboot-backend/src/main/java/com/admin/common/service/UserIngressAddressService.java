package com.admin.common.service;

import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelEntryDomainService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Builds request-local display addresses. Never mutate the routing entities stored in the database. */
@Service
public class UserIngressAddressService {
    public static final Address UNASSIGNED = new Address("UNASSIGNED", null, null, null);
    @Resource private UserTunnelMapper userTunnelMapper;
    @Resource private TunnelMapper tunnelMapper;
    @Resource private TunnelEntryDomainService domainService;

    public Map<Integer, Address> forUser(Integer userId) {
        if (userId == null) return Map.of();
        long now = System.currentTimeMillis();
        Map<Integer, UserTunnel> assignments = userTunnelMapper.selectList(
                        new QueryWrapper<UserTunnel>().eq("user_id", userId)).stream()
                .filter(grant -> Objects.equals(grant.getUserId(), userId))
                .filter(grant -> Objects.equals(grant.getStatus(), 1)
                        && (grant.getExpTime() == null || grant.getExpTime() > now))
                .collect(Collectors.toMap(UserTunnel::getTunnelId, grant -> grant, (first, ignored) -> first));
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        if (assignments.isEmpty()) query.eq("owner_user_id", userId);
        else query.and(part -> part.eq("owner_user_id", userId)
                .or(shared -> shared.isNull("owner_user_id").in("id", assignments.keySet())));
        List<Tunnel> tunnels = tunnelMapper.selectList(query);
        Map<Integer, Address> addresses = new HashMap<>();
        for (Tunnel tunnel : tunnels) {
            boolean owned = Objects.equals(tunnel.getOwnerUserId(), userId.longValue());
            // Recheck ownership in Java too: a grant never authorizes another user's private tunnel.
            if (!owned && (tunnel.getOwnerUserId() != null || !Objects.equals(tunnel.getStatus(), 1))) continue;
            UserTunnel grant = assignments.get(tunnel.getId().intValue());
            if (grant == null && !owned) continue;
            addresses.put(tunnel.getId().intValue(), resolve(tunnel.getInIp(), grant));
        }
        return addresses;
    }

    private Address resolve(String originalAddress, UserTunnel grant) {
        // Blank is the historical, administrator-authorized NONE mode. A missing grant is
        // allowed only for the tunnel owner above; it never grants a shared raw ingress.
        String mode = grant == null || StringUtils.isBlank(grant.getEntryAddressMode())
                ? TunnelEntryDomainService.ENTRY_ADDRESS_MODE_NONE
                : grant.getEntryAddressMode().trim().toUpperCase(Locale.ROOT);
        if (TunnelEntryDomainService.ENTRY_ADDRESS_MODE_NONE.equals(mode)) {
            return new Address(mode, originalAddress, null, null);
        }
        if (!TunnelEntryDomainService.ENTRY_ADDRESS_MODE_CUSTOM.equals(mode)
                && !TunnelEntryDomainService.ENTRY_ADDRESS_MODE_DEFAULT.equals(mode)) return UNASSIGNED;
        String domain = domainService.resolveAssignedDomain(grant);
        if (StringUtils.isBlank(domain)) domain = null;
        return new Address(mode, domain, domain, grant.getEntryDomainId());
    }

    public record Address(String mode, String address, String domain, Long domainId) {}
}

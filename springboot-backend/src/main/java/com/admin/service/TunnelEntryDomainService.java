package com.admin.service;

import com.admin.common.dto.TunnelEntryDomainDto;
import com.admin.common.lang.R;
import com.admin.entity.UserTunnel;
import com.admin.entity.TunnelEntryDomain;

import java.util.List;
import java.util.Map;
import java.util.Collection;

public interface TunnelEntryDomainService {

    TunnelEntryDomain getDomainById(Long id);

    String ENTRY_ADDRESS_MODE_NONE = "NONE";
    String ENTRY_ADDRESS_MODE_DEFAULT = "DEFAULT";
    String ENTRY_ADDRESS_MODE_CUSTOM = "CUSTOM";

    R getTunnelEntryDomains(Long tunnelId);

    R createTunnelEntryDomain(TunnelEntryDomainDto dto);

    R setDefaultTunnelEntryDomain(Long id);

    R deleteTunnelEntryDomain(Long id);

    R validateInitialDomains(List<String> domains, String defaultDomain);

    void createInitialDomains(Long tunnelId, List<String> domains, String defaultDomain);

    R validateUserEntryAssignment(Integer tunnelId, String mode, Long domainId);

    String resolveAssignedDomain(UserTunnel userTunnel);

    /** Resolve one user's assignments, keyed by tunnel ID. Missing or invalid domains are omitted. */
    Map<Integer, String> resolveAssignedDomains(Collection<UserTunnel> userTunnels);
}

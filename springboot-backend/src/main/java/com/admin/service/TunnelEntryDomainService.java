package com.admin.service;

import com.admin.common.dto.TunnelEntryDomainDto;
import com.admin.common.lang.R;
import com.admin.entity.UserTunnel;

import java.util.List;

public interface TunnelEntryDomainService {

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
}

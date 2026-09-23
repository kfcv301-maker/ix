package com.admin.common.dto;

import lombok.Data;

@Data
public class TunnelEntryDomainView {

    private Long id;

    private Long tunnelId;

    private String domain;

    private Boolean defaultDomain;
}

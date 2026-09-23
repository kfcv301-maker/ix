package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class TunnelEntryDomainDto {

    @NotNull(message = "隧道ID不能为空")
    private Long tunnelId;

    @NotBlank(message = "解析域名不能为空")
    private String domain;

    /** When true, the new domain becomes the tunnel default. */
    private Boolean defaultDomain;
}

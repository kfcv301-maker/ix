package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class TunnelEntryDomainQueryDto {

    @NotNull(message = "隧道ID不能为空")
    private Long tunnelId;
}

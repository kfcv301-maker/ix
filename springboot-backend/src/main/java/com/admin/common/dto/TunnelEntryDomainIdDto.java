package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class TunnelEntryDomainIdDto {

    @NotNull(message = "解析域名ID不能为空")
    private Long id;
}

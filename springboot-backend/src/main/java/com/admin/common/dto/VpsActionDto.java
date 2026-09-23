package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class VpsActionDto {

    @NotNull(message = "VPS ID不能为空")
    private Long id;
}

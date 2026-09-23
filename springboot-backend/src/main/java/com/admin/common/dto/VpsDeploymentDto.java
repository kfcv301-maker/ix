package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** A deployment request is intentionally restricted to a reviewed template. */
@Data
public class VpsDeploymentDto {

    @NotNull(message = "VPS ID不能为空")
    private Long id;

    @NotBlank(message = "部署类型不能为空")
    private String taskType;
}

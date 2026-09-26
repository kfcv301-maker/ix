package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/** Starts the sole reviewed VPS operation: installing the panel backend. */
@Data
public class VpsDeploymentDto {

    @NotNull(message = "VPS ID不能为空")
    private Long id;

    /**
     * Accepted only so older browser bundles can call the endpoint. The server
     * deliberately ignores it and always installs the backend.
     */
    @Deprecated
    private String taskType;
}

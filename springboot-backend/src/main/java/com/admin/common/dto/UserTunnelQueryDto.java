package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class UserTunnelQueryDto {

    @NotNull
    private Integer userId;

} 
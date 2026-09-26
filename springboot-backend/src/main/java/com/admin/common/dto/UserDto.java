package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
public class UserDto {

    @NotBlank(message = "用户名不能为空")
    private String user;

    @NotBlank(message = "密码不能为空")
    private String pwd;

    @NotNull(message = "流量不能为空")
    @Min(value = 0, message = "流量不能小于0")
    private Long flow;

    @NotNull(message = "转发数量不能为空")
    @Min(value = 0, message = "转发数量不能小于0")
    private Integer num;

    @NotNull(message = "过期时间不能为空")
    private Long expTime;

    @NotNull(message = "流量重置时间不能为空")
    private Long flowResetTime;

    private Integer status;
} 
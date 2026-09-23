package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/** Blank SSH password deliberately means "retain the encrypted password". */
@Data
public class VpsHostUpdateDto {

    @NotNull(message = "VPS ID不能为空")
    private Long id;

    @NotBlank(message = "VPS 名称不能为空")
    @Size(max = 100, message = "VPS 名称不能超过100个字符")
    private String name;

    @NotBlank(message = "SSH 地址不能为空")
    @Size(max = 255, message = "SSH 地址不能超过255个字符")
    private String host;

    @NotNull(message = "SSH 端口不能为空")
    @Min(value = 1, message = "SSH 端口必须在1到65535之间")
    @Max(value = 65535, message = "SSH 端口必须在1到65535之间")
    private Integer sshPort;

    @NotBlank(message = "SSH 用户名不能为空")
    @Size(max = 100, message = "SSH 用户名不能超过100个字符")
    private String sshUsername;

    @Size(max = 4096, message = "SSH 密码长度异常")
    private String sshPassword;

    @Size(max = 1000, message = "备注不能超过1000个字符")
    private String remark;

    /** Only respected for ADMIN-origin hosts and administrator callers. */
    private Long assignedUserId;
}

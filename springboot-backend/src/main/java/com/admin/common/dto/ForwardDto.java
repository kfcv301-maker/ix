package com.admin.common.dto;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

@Data
public class ForwardDto {

    @Pattern(regexp = "[A-Za-z0-9_-]{16,64}", message = "创建请求标识格式不正确")
    private String requestId;

    @NotBlank(message = "转发名称不能为空")
    private String name;
    
    @NotNull(message = "隧道ID不能为空")
    private Integer tunnelId;
    
    @NotBlank(message = "远程地址不能为空")
    private String remoteAddr;

    /** Optional hosted VPS used as this forwarding rule's target. */
    @Positive(message = "关联 VPS ID必须为正数")
    private Long vpsHostId;

    private String strategy;

    @Min(value = 0, message = "Proxy Protocol 仅支持关闭、v1 或 v2")
    @Max(value = 2, message = "Proxy Protocol 仅支持关闭、v1 或 v2")
    private Integer proxyProtocol;
    
    /**
     * 入口端口（可选，为空时自动分配）
     */
    @Min(value = 1, message = "端口号不能小于1")
    @Max(value = 65535, message = "端口号不能大于65535")
    private Integer inPort;

    private String interfaceName;

}

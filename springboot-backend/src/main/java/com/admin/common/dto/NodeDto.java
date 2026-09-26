package com.admin.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Data
public class NodeDto {

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @NotBlank(message = "入口IP不能为空")
    private String ip;

    @NotBlank(message = "服务器ip不能为空")
    private String serverIp;

    @NotNull(message = "起始端口不能为空")
    @Min(value = 1, message = "起始端口必须大于0")
    @Max(value = 65535, message = "起始端口不能超过65535")
    private Integer portSta;

    @NotNull(message = "结束端口不能为空")
    @Min(value = 1, message = "结束端口必须大于0")
    @Max(value = 65535, message = "结束端口不能超过65535")
    private Integer portEnd;

    /** tiny, small, balanced or standard; balanced is used when omitted. */
    private String tcpTuningProfile;

    /** Enables node-local adaptive TCP limits after installation. */
    private Boolean tcpTuningAutoEnabled;

    private String tcpTuningProfileMin;

    private String tcpTuningProfileMax;

    /** DDNS settings are saved with the node, not asked again when copying its command. */
    private Boolean ddnsEnabled;

    private String cfApiToken;

    private String cfRecordName;

}

package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class NodeInstallCommandDto {

    @NotNull(message = "节点ID不能为空")
    private Long id;

    /**
     * null 保持旧客户端的行为；true 配置 DDNS；false 移除现有 DDNS 配置。
     */
    private Boolean ddnsEnabled;

    private String cfApiToken;

    private String cfRecordName;

    /**
     * 节点安装时由管理员选择的 TCP 调优档位：tiny、small、balanced 或 standard。
     * 为空时保留安装脚本的旧版自动选择行为，兼容旧客户端。
     */
    private String tcpTuningProfile;

    /**
     * 管理员当前访问面板的公网 URL。前端自动填写，不要求用户手动配置 IP 或端口。
     */
    private String panelUrl;
}

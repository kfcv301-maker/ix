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
}

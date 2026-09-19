package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class NodeInstallCommandDto {

    @NotNull(message = "节点ID不能为空")
    private Long id;

    /**
     * Legacy fields are retained only so older browser bundles do not fail
     * deserialization. New commands always use the settings saved on Node.
     */
    @Deprecated
    private Boolean ddnsEnabled;

    @Deprecated
    private String cfApiToken;

    @Deprecated
    private String cfRecordName;

    @Deprecated
    private String tcpTuningProfile;

    /**
     * 管理员当前访问面板的公网 URL。前端自动填写，不要求用户手动配置 IP 或端口。
     */
    private String panelUrl;
}

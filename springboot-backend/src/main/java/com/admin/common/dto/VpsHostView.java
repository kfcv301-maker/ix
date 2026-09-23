package com.admin.common.dto;

import lombok.Data;

/** Safe browser projection: deliberately contains no encrypted credential. */
@Data
public class VpsHostView {

    private Long id;
    private String name;
    private String host;
    private Integer sshPort;
    private String sshUsername;
    private String origin;
    private Long ownerUserId;
    private String ownerUserName;
    private Long assignedUserId;
    private String assignedUserName;
    private String remark;
    private String sshFingerprint;
    private String healthStatus;
    private Long lastCheckTime;
    private String lastCheckMessage;
    private Long lastLatencyMs;
    private Long createdTime;
    private Long updatedTime;
    private Boolean canOperate;
    private Boolean canManage;
    private Boolean canAssign;
}

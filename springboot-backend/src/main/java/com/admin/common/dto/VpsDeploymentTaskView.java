package com.admin.common.dto;

import lombok.Data;

@Data
public class VpsDeploymentTaskView {

    private Long id;
    private Long vpsId;
    private Long requestedByUserId;
    private String requestedByUserName;
    private String taskType;
    private String taskStatus;
    private String outputLog;
    private Long startedTime;
    private Long finishedTime;
    private Long createdTime;
    private Long updatedTime;
}

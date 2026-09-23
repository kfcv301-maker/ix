package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Audit-friendly record for a predefined VPS deployment task. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vps_deployment_task")
public class VpsDeploymentTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long vpsId;

    private Long requestedByUserId;

    /** docker or flux_panel. Arbitrary shell commands are intentionally not stored here. */
    private String taskType;

    /** pending, running, succeeded, failed */
    private String taskStatus;

    /** Bounded, redacted execution output. */
    private String outputLog;

    private Long startedTime;

    private Long finishedTime;
}

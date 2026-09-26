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

    /** backend for new tasks; older template labels remain readable in history. */
    private String taskType;

    /** pending, running, succeeded, failed */
    private String taskStatus;

    /** Non-null only while pending/running; unique per VPS to serialize deployment. */
    private String activeLock;

    /** Bounded, redacted execution output. */
    private String outputLog;

    private Long startedTime;

    private Long finishedTime;
}

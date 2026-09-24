package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** One idempotent command for one endpoint of a forwarding rule. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("forward_sync_task")
public class ForwardSyncTask extends BaseEntity {

    private String operationId;
    private Long forwardId;
    private Long nodeId;

    /** ingress or egress */
    private String endpoint;
    /** pause, resume or delete */
    private String operation;
    /** Service name captured when the operation is requested. */
    private String serviceName;
    private Integer targetStatus;

    /** pending, running, succeeded or cancelled */
    private String taskStatus;
    private Integer attempts;
    private Long nextRetryTime;
    private String lastError;
}

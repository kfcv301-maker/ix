package com.admin.common.dto;

import lombok.Data;

/** Compact current-operation state attached to a forwarding record for UI use. */
@Data
public class ForwardSyncSummary {
    private Long forwardId;
    private String operationId;
    private String operation;
    private Integer totalTasks;
    private Integer pendingTasks;
    private Integer runningTasks;
    private Integer retriedTasks;
    private String lastError;
    private Long latestTime;
}

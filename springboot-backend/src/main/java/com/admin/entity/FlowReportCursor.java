package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Last accepted traffic report for one service on one node.
 *
 * Keeping a cursor rather than one row per report makes retries idempotent
 * without turning high-frequency traffic accounting into an ever-growing log.
 */
@Data
@TableName("flow_report_cursor")
public class FlowReportCursor {

    private Long nodeId;

    private String serviceName;

    private String sessionId;

    private Long sessionStartedAt;

    private Long lastSequence;

    private Long createdTime;

    private Long updatedTime;
}

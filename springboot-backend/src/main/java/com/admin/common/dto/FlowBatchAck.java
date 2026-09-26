package com.admin.common.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** Identifies one agent report acknowledged inside a batch response. */
@Data
public class FlowBatchAck {
    private String n;

    @JSONField(name = "i")
    private String reportSessionId;

    @JSONField(name = "q")
    private Long reportSequence;

    @JSONField(name = "b")
    private Long reportSessionStartedAt;

    public static FlowBatchAck from(FlowDto report) {
        FlowBatchAck ack = new FlowBatchAck();
        ack.setN(report == null ? null : report.getN());
        ack.setReportSessionId(report == null ? null : report.getReportSessionId());
        ack.setReportSequence(report == null ? null : report.getReportSequence());
        ack.setReportSessionStartedAt(report == null ? null : report.getReportSessionStartedAt());
        return ack;
    }
}

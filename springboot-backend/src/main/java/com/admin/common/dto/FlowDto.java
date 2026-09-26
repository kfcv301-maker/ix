package com.admin.common.dto;


import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class FlowDto {
    // 转发id_类型
    private String n;

    // 上传流量
    private Long u;

    // 下载流量
    private Long d;

    /** Agent boot/session identifier, used with q for idempotent reports. */
    @JSONField(name = "i")
    private String reportSessionId;

    /** Per-service monotonically increasing report sequence. */
    @JSONField(name = "q")
    private Long reportSequence;

    /** Millisecond timestamp at which the agent session started. */
    @JSONField(name = "b")
    private Long reportSessionStartedAt;
}

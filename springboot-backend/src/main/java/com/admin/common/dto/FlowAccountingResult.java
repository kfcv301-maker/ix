package com.admin.common.dto;

import lombok.Getter;

/** Outcome of one authenticated agent traffic report. */
@Getter
public class FlowAccountingResult {

    private final boolean accepted;
    private final boolean duplicate;
    private final boolean upgradeRequired;
    private final String reason;
    private final Long userId;
    private final Long userTunnelId;
    private final boolean pauseWorkQueued;

    private FlowAccountingResult(boolean accepted, boolean duplicate, boolean upgradeRequired,
                                 String reason, Long userId, Long userTunnelId, boolean pauseWorkQueued) {
        this.accepted = accepted;
        this.duplicate = duplicate;
        this.upgradeRequired = upgradeRequired;
        this.reason = reason;
        this.userId = userId;
        this.userTunnelId = userTunnelId;
        this.pauseWorkQueued = pauseWorkQueued;
    }

    public static FlowAccountingResult accepted(Long userId, Long userTunnelId, boolean pauseWorkQueued) {
        return new FlowAccountingResult(true, false, false, null, userId, userTunnelId, pauseWorkQueued);
    }

    public static FlowAccountingResult duplicate() {
        return new FlowAccountingResult(false, true, false, null, null, null, false);
    }

    public static FlowAccountingResult rejected(String reason) {
        return new FlowAccountingResult(false, false, false, reason, null, null, false);
    }

    public static FlowAccountingResult upgradeRequired() {
        return new FlowAccountingResult(false, false, true,
                "节点流量上报协议过旧，缺少幂等序列", null, null, false);
    }
}

package com.admin.common.dto;

import lombok.Data;

import java.math.BigDecimal;

/** Minimal relational snapshot needed to authenticate and price one report. */
@Data
public class FlowAccountingContext {

    private Long forwardId;
    private Integer forwardUserId;
    private Integer forwardTunnelId;
    private Integer forwardStatus;

    private Long tunnelId;
    private Integer tunnelStatus;
    private Integer tunnelFlow;
    private BigDecimal trafficRatio;
    private Integer ingressNode;

    private Long ownerId;
    private Integer ownerRoleId;
    private Integer ownerStatus;
    private Long ownerExpTime;
    private Long ownerFlow;
    private Long ownerInFlow;
    private Long ownerOutFlow;

    private Integer userTunnelId;
    private Integer userTunnelUserId;
    private Integer userTunnelTunnelId;
    private Integer userTunnelStatus;
    private Long userTunnelExpTime;
    private Long userTunnelFlow;
    private Long userTunnelInFlow;
    private Long userTunnelOutFlow;

    /** Allows sequenced reports that were already in flight before a pause ACK. */
    private Long flowGraceUntil;
}

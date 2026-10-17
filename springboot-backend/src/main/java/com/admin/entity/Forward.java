package com.admin.entity;

import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private Integer inPort;

    private Integer outPort;

    private String remoteAddr;

    /** Optional VPS record that supplies the target service for this forward. */
    private Long vpsHostId;

    private String interfaceName;

    private String strategy;

    /** Outbound TCP PROXY protocol: 0 disabled, 1 v1, 2 v2. */
    private Integer proxyProtocol;

    private Long inFlow;

    private Long outFlow;

    /** Short sequenced-report window after every endpoint acknowledged a pause. */
    private Long flowGraceUntil;

    private Integer inx;

}

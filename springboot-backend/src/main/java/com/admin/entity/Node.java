package com.admin.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.TableField;
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
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    /** Null for administrator inventory; regular users own the nodes they add. */
    private Long ownerUserId;

    @TableField(exist = false)
    private Boolean canManage;

    private String secret;

    private String ip;

    private String serverIp;

    private String version;

    private Integer portSta;

    private Integer portEnd;

    private Integer http;

    private Integer tls;

    private Integer socks;

    /** TCP tuning preset selected when the node is created or edited. */
    private String tcpTuningProfile;

    /** Enables the node-local memory guard within the administrator's bounds. */
    private Integer tcpTuningAutoEnabled;

    /** Lowest TCP tuning preset that the node-local memory guard may apply. */
    private String tcpTuningProfileMin;

    /** Highest TCP tuning preset that the node-local memory guard may apply. */
    private String tcpTuningProfileMax;

    /** Whether the generated node installation command should configure DDNS. */
    private Integer ddnsEnabled;

    /**
     * AES-GCM encrypted Cloudflare token. Never serialize it in node responses
     * or request logs.
     */
    @JsonIgnore
    private String ddnsToken;

    /** The DNS record managed by this node when DDNS is enabled. */
    private String ddnsRecordName;

}

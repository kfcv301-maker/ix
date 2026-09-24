package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A server entrusted to the panel for SSH management.
 *
 * <p>"origin" determines ownership semantics rather than merely who created
 * the row: USER hosts remain available to their owner and every administrator;
 * ADMIN hosts become visible to a regular user only after assignment.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vps_host")
public class VpsHost extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    /** IPv4, IPv6 literal, or DNS host name used for SSH. */
    private String host;

    private Integer sshPort;

    private String sshUsername;

    /** AES-GCM encrypted password. It must never be serialized or logged. */
    @JsonIgnore
    private String sshPassword;

    /** USER for a user-submitted VPS, ADMIN for panel/administrator inventory. */
    private String origin;

    /** Account that originally entered this VPS into the panel. */
    private Long createdByUserId;

    /** The regular user who submitted a USER-origin host; null for ADMIN inventory. */
    private Long ownerUserId;

    /** The regular user currently assigned an ADMIN-origin VPS, if any. */
    private Long assignedUserId;

    private String remark;

    /** Pinned after the first successful SSH handshake to detect host-key changes. */
    private String sshFingerprint;

    /** unknown, online, offline, or fingerprint_changed. */
    private String healthStatus;

    private Long lastCheckTime;

    private String lastCheckMessage;

    private Long lastLatencyMs;
}

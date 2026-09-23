package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A public, DNS-resolved entry domain that an administrator may attach to a
 * tunnel. It is deliberately independent from node DDNS configuration: this
 * table only controls which already-resolved address is shown to a user.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tunnel_entry_domain")
public class TunnelEntryDomain extends BaseEntity {

    private Long tunnelId;

    private String domain;

    /** 1 means this is the tunnel's default domain; 0 means it is optional. */
    @TableField("is_default")
    private Integer isDefault;
}

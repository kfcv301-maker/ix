package com.admin.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

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
public class UserTunnel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private Integer tunnelId;

    private Long flow;

    private Long inFlow;

    private Long outFlow;

    private Long flowResetTime;

    private Long expTime;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer speedId;

    private Integer num;

    /**
     * NONE shows the tunnel's original ingress address, DEFAULT resolves the
     * tunnel's current default domain, and CUSTOM uses entryDomainId.
     */
    private String entryAddressMode;

    /** A tunnel_entry_domain ID when entryAddressMode is CUSTOM. */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long entryDomainId;

    private Integer status;

}

package com.admin.common.dto;

import lombok.Data;

@Data
public class TunnelListDto {

    private Integer id;

    private String name;
    
    /**
     * 入口IP
     */
    private String ip;

    /** The original tunnel ingress, retained when a user receives a domain. */
    private String originalIp;

    /** A normal user only receives their own NONE / DEFAULT / CUSTOM choice. */
    private String entryAddressMode;

    private Long entryDomainId;

    /** The effective assigned domain; null means the raw ingress is used. */
    private String entryDomain;
    
    /**
     * 入口节点端口起始范围
     */
    private Integer inNodePortSta;
    
    /**
     * 入口节点端口结束范围
     */
    private Integer inNodePortEnd;

    /**
     * 隧道类型（1-端口转发，2-隧道转发）
     */
    private Integer type;
    
    /**
     * 协议类型
     */
    private String protocol;
}

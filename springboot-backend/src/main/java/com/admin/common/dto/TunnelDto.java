package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.util.List;

@Data
public class TunnelDto {
    
    @NotBlank(message = "隧道名称不能为空")
    private String name;
    
    private Long inNodeId;

    /**
     * All ingress nodes for this tunnel. inNodeId is retained as the primary
     * ingress for backwards compatibility and must be included in this list.
     */
    private List<Long> entryNodeIds;

    /**
     * Legacy multi-ingress public addresses. Kept so API clients from earlier
     * releases can still create their existing IP-based tunnels.
     */
    private List<String> entryIps;

    /**
     * One public hostname for a multi-ingress tunnel. The hostname is stored
     * in the existing inIp column; its A/AAAA records are managed by the user
     * and may point to every selected ingress node.
     */
    private String entryDomain;

    /**
     * Optional per-tunnel domain pool. These are only panel display addresses;
     * DNS/DDNS records and node configuration are intentionally untouched.
     */
    private List<String> accessDomains;

    /** One item from accessDomains. When omitted, the first domain is default. */
    private String defaultAccessDomain;

    // 出口节点ID，当type=1时可以为空，会自动设置为入口节点ID
    private Long outNodeId;
    
    @NotNull(message = "隧道类型不能为空")
    private Integer type;
    
    @NotNull(message = "流量计算类型不能为空")
    private Integer flow;
    
    // 流量倍率，默认为1.0
    @DecimalMin(value = "0.0", inclusive = false, message = "流量倍率必须大于0.0")
    @DecimalMax(value = "100.0", message = "流量倍率不能大于100.0")
    private BigDecimal trafficRatio;

    private String interfaceName;
    
    // 协议类型，默认为tls
    private String protocol;
    
    // TCP监听地址，默认为0.0.0.0
    private String tcpListenAddr = "0.0.0.0";
    
    // UDP监听地址，默认为0.0.0.0
    private String udpListenAddr = "0.0.0.0";
}

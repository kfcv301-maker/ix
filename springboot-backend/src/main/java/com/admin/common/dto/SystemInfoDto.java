package com.admin.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 系统信息DTO
 * 对应Go客户端上报的系统信息结构
 */
@Data
public class SystemInfoDto {
    
    /**
     * 主机IP地址
     */
    @JsonProperty("host_ip")
    private String hostIp;
    
    /**
     * 开机时间（秒）
     */
    @JsonProperty("uptime")
    private Long uptime;
    
    /**
     * 接收字节数
     */
    @JsonProperty("bytes_received")
    private Long bytesReceived;
    
    /**
     * 发送字节数
     */
    @JsonProperty("bytes_transmitted")
    private Long bytesTransmitted;
    
    /**
     * CPU使用率（百分比）
     */
    @JsonProperty("cpu_usage")
    private Double cpuUsage;
    
    /**
     * 内存使用率（百分比）
     */
    @JsonProperty("memory_usage")
    private Double memoryUsage;

    /** CPU逻辑核心数 */
    @JsonProperty("cpu_cores")
    private Integer cpuCores;

    /** 内存总量（字节） */
    @JsonProperty("memory_total")
    private Long memoryTotal;

    /** 根分区总量（字节） */
    @JsonProperty("disk_total")
    private Long diskTotal;

    /** 内存已用、可用与缓存容量（字节）。旧版 Agent 不会发送这些字段。 */
    @JsonProperty("memory_used")
    private Long memoryUsed;

    @JsonProperty("memory_available")
    private Long memoryAvailable;

    @JsonProperty("memory_cached")
    private Long memoryCached;

    /** Swap 已用/总量（字节）。 */
    @JsonProperty("swap_used")
    private Long swapUsed;

    @JsonProperty("swap_total")
    private Long swapTotal;

    /** Agent 进程自身的内存与运行时指标。 */
    @JsonProperty("agent_rss")
    private Long agentRss;

    @JsonProperty("agent_heap_alloc")
    private Long agentHeapAlloc;

    @JsonProperty("goroutines")
    private Long goroutines;

    /** 根分区已用容量和使用率。 */
    @JsonProperty("disk_used")
    private Long diskUsed;

    @JsonProperty("disk_used_percent")
    private Double diskUsedPercent;

    /** 1 分钟系统负载。 */
    @JsonProperty("load_1")
    private Double load1;

    /** Agent/GOST 进程内的 TCP 已建立套接字与 UDP 活动套接字。 */
    @JsonProperty("tcp_connections")
    private Long tcpConnections;

    @JsonProperty("udp_connections")
    private Long udpConnections;
    
    /**
     * 上报时间戳
     */
    private Long timestamp;
    
    public SystemInfoDto() {
        this.timestamp = System.currentTimeMillis();
    }
}

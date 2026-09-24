package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * A database-backed claim for one TCP/UDP port on one node.  The unique
 * node/port key is the concurrency boundary for forward creation; an in-JVM
 * availability cache could not provide that guarantee across requests.
 */
@Data
@TableName("forward_port_reservation")
public class ForwardPortReservation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long nodeId;
    private Integer port;
    private Long forwardId;
    /** ingress or egress; informational because node_id + port is unique. */
    private String endpoint;
    private Long createdTime;
}

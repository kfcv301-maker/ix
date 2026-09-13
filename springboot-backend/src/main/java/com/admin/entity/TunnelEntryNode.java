package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * An ingress node belonging to a tunnel. The original tunnel.in_node_id stays
 * as the primary ingress so old installations and API clients remain valid.
 */
@Data
@TableName("tunnel_entry_node")
public class TunnelEntryNode extends BaseEntity {

    private Long tunnelId;

    private Long nodeId;
}

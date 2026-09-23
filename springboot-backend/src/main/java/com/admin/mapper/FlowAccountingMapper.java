package com.admin.mapper;

import com.admin.common.dto.FlowAccountingContext;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Hot-path flow queries: keep the authorization graph to one indexed read. */
public interface FlowAccountingMapper {

    @Select("SELECT f.id AS forwardId, f.user_id AS forwardUserId, f.tunnel_id AS forwardTunnelId, "
            + "f.status AS forwardStatus, t.id AS tunnelId, t.status AS tunnelStatus, t.flow AS tunnelFlow, "
            + "t.traffic_ratio AS trafficRatio, u.id AS ownerId, u.role_id AS ownerRoleId, "
            + "ut.id AS userTunnelId, ut.user_id AS userTunnelUserId, ut.tunnel_id AS userTunnelTunnelId, "
            + "CASE WHEN t.in_node_id = #{nodeId} OR EXISTS (SELECT 1 FROM tunnel_entry_node ten "
            + "WHERE ten.tunnel_id = t.id AND ten.node_id = #{nodeId}) THEN 1 ELSE 0 END AS ingressNode "
            + "FROM forward f INNER JOIN tunnel t ON t.id = f.tunnel_id "
            + "INNER JOIN `user` u ON u.id = f.user_id "
            + "LEFT JOIN user_tunnel ut ON ut.id = #{userTunnelId} "
            + "WHERE f.id = #{forwardId}")
    FlowAccountingContext selectContext(@Param("forwardId") Long forwardId,
                                        @Param("userTunnelId") Long userTunnelId,
                                        @Param("nodeId") Long nodeId);

    @Update("UPDATE forward f INNER JOIN `user` u ON u.id = f.user_id "
            + "SET f.in_flow = f.in_flow + #{download}, f.out_flow = f.out_flow + #{upload}, "
            + "u.in_flow = u.in_flow + #{download}, u.out_flow = u.out_flow + #{upload} "
            + "WHERE f.id = #{forwardId} AND f.user_id = #{userId}")
    int incrementForwardAndUser(@Param("forwardId") Long forwardId,
                                @Param("userId") Long userId,
                                @Param("download") Long download,
                                @Param("upload") Long upload);

    @Update("UPDATE user_tunnel SET in_flow = in_flow + #{download}, out_flow = out_flow + #{upload} "
            + "WHERE id = #{userTunnelId} AND user_id = #{userId} AND tunnel_id = #{tunnelId}")
    int incrementUserTunnel(@Param("userTunnelId") Integer userTunnelId,
                            @Param("userId") Long userId,
                            @Param("tunnelId") Integer tunnelId,
                            @Param("download") Long download,
                            @Param("upload") Long upload);
}

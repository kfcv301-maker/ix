package com.admin.mapper;

import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Narrow, explicit dependency cleanup; historical user flow totals are retained. */
public interface NodeDeletionMapper {
    @Select("SELECT * FROM node WHERE id = #{id} FOR UPDATE")
    Node lockNode(Long id);

    @Select("SELECT * FROM tunnel WHERE id = #{id} FOR UPDATE")
    Tunnel lockTunnel(Long id);

    @Select("SELECT * FROM tunnel WHERE in_node_id = #{nodeId} OR out_node_id = #{nodeId} "
            + "OR id IN (SELECT tunnel_id FROM tunnel_entry_node WHERE node_id = #{nodeId}) ORDER BY id FOR UPDATE")
    List<Tunnel> affectedTunnels(Long nodeId);

    @Delete("DELETE FROM forward_pause_task WHERE forward_id = #{id}")
    int deletePauseTasks(Long id);

    @Delete("DELETE FROM user_tunnel_alias WHERE tunnel_id = #{id}")
    int deleteAliases(Long id);

    @Delete("DELETE FROM flow_report_cursor WHERE node_id = #{id}")
    int deleteNodeCursors(Long id);

    @Delete("DELETE FROM forward_port_reservation WHERE node_id = #{id}")
    int deleteNodeReservations(Long id);
}

package com.admin.mapper;

import com.admin.entity.ForwardPortReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ForwardPortReservationMapper extends BaseMapper<ForwardPortReservation> {

    @Insert("INSERT IGNORE INTO forward_port_reservation "
            + "(node_id, port, forward_id, endpoint, created_time) "
            + "VALUES (#{nodeId}, #{port}, #{forwardId}, #{endpoint}, #{createdTime})")
    int insertIgnore(@Param("nodeId") Long nodeId, @Param("port") Integer port,
                     @Param("forwardId") Long forwardId, @Param("endpoint") String endpoint,
                     @Param("createdTime") Long createdTime);

    @Select("SELECT * FROM forward_port_reservation WHERE node_id = #{nodeId} AND port = #{port} LIMIT 1")
    ForwardPortReservation selectByNodeAndPort(@Param("nodeId") Long nodeId, @Param("port") Integer port);

    @Select("SELECT port FROM forward_port_reservation WHERE node_id = #{nodeId}")
    List<Integer> selectPortsForNode(@Param("nodeId") Long nodeId);

    @Select("SELECT port FROM forward_port_reservation WHERE node_id = #{nodeId} AND forward_id <> #{forwardId}")
    List<Integer> selectPortsForNodeExcept(@Param("nodeId") Long nodeId, @Param("forwardId") Long forwardId);

    @Delete("DELETE FROM forward_port_reservation WHERE forward_id = #{forwardId}")
    int deleteByForwardId(@Param("forwardId") Long forwardId);

    @Delete("DELETE FROM forward_port_reservation WHERE id = #{id} AND forward_id = #{forwardId}")
    int deleteOwnedReservation(@Param("id") Long id, @Param("forwardId") Long forwardId);
}

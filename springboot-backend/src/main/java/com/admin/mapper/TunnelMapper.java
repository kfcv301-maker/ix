package com.admin.mapper;

import com.admin.entity.Tunnel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface TunnelMapper extends BaseMapper<Tunnel> {
    @Select("SELECT COUNT(*) FROM traffic_pool_tunnel WHERE tunnel_id=#{id}")
    int countPoolTunnel(@Param("id") Long id);
}

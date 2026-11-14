package com.admin.mapper;

import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface NodeMapper extends BaseMapper<Node> {
    @Select("SELECT COUNT(*) FROM traffic_pool_host WHERE node_id = #{nodeId}")
    int countPoolHost(Long nodeId);
    @Select("SELECT ssh_port FROM traffic_pool_host WHERE node_id = #{nodeId}")
    Integer poolSshPort(Long nodeId);
}

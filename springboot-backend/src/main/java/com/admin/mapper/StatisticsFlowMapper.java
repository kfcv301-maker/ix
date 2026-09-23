package com.admin.mapper;

import com.admin.entity.StatisticsFlow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-08-14
 */
public interface StatisticsFlowMapper extends BaseMapper<StatisticsFlow> {

    /** One indexed query replaces one latest-row lookup per user each hour. */
    @Select("<script>"
            + "SELECT sf.user_id AS userId, sf.total_flow AS totalFlow "
            + "FROM statistics_flow sf INNER JOIN ("
            + "SELECT user_id, MAX(id) AS last_id FROM statistics_flow "
            + "WHERE user_id IN "
            + "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach> "
            + "GROUP BY user_id"
            + ") latest ON latest.last_id = sf.id"
            + "</script>")
    List<StatisticsFlow> selectLatestByUserIds(@Param("userIds") Collection<Long> userIds);
}

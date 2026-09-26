package com.admin.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface UserTunnelAliasMapper {
    @Select("SELECT alias_id FROM user_tunnel_alias WHERE canonical_id = #{canonicalId} "
            + "AND user_id = #{userId} AND tunnel_id = #{tunnelId} ORDER BY alias_id")
    List<Integer> selectAliases(@Param("canonicalId") Integer canonicalId,
                                @Param("userId") Integer userId, @Param("tunnelId") Integer tunnelId);
}

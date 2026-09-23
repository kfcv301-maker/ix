package com.admin.mapper;

import com.admin.entity.FlowReportCursor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SQL kept explicit because the cursor has a composite database key. */
public interface FlowReportCursorMapper {

    @Insert("INSERT IGNORE INTO flow_report_cursor "
            + "(node_id, service_name, session_id, session_started_at, last_sequence, created_time, updated_time) "
            + "VALUES (#{nodeId}, #{serviceName}, #{sessionId}, #{sessionStartedAt}, 0, #{now}, #{now})")
    int insertIfAbsent(@Param("nodeId") Long nodeId,
                       @Param("serviceName") String serviceName,
                       @Param("sessionId") String sessionId,
                       @Param("sessionStartedAt") Long sessionStartedAt,
                       @Param("now") Long now);

    @Select("SELECT node_id, service_name, session_id, session_started_at, last_sequence, created_time, updated_time "
            + "FROM flow_report_cursor WHERE node_id = #{nodeId} AND service_name = #{serviceName} FOR UPDATE")
    FlowReportCursor selectForUpdate(@Param("nodeId") Long nodeId,
                                     @Param("serviceName") String serviceName);

    @Update("UPDATE flow_report_cursor SET session_id = #{sessionId}, session_started_at = #{sessionStartedAt}, "
            + "last_sequence = #{lastSequence}, updated_time = #{now} "
            + "WHERE node_id = #{nodeId} AND service_name = #{serviceName}")
    int updateCursor(@Param("nodeId") Long nodeId,
                     @Param("serviceName") String serviceName,
                     @Param("sessionId") String sessionId,
                     @Param("sessionStartedAt") Long sessionStartedAt,
                     @Param("lastSequence") Long lastSequence,
                     @Param("now") Long now);

    @Delete("DELETE FROM flow_report_cursor WHERE updated_time < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") Long cutoff);
}

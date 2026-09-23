package com.admin.mapper;

import com.admin.entity.ForwardPauseTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ForwardPauseTaskMapper extends BaseMapper<ForwardPauseTask> {

    @Insert("INSERT IGNORE INTO forward_pause_task "
            + "(forward_id, task_status, attempts, last_error, created_time, updated_time, status) "
            + "VALUES (#{forwardId}, 'pending', 0, NULL, #{now}, #{now}, 1)")
    int insertIfAbsent(@Param("forwardId") Long forwardId, @Param("now") Long now);

    /** Inserts all currently eligible pauses inside the accounting transaction. */
    @Insert("INSERT IGNORE INTO forward_pause_task "
            + "(forward_id, task_status, attempts, last_error, created_time, updated_time, status) "
            + "SELECT f.id, 'pending', 0, NULL, #{now}, #{now}, 1 "
            + "FROM forward f INNER JOIN `user` u ON u.id = f.user_id "
            + "WHERE f.user_id = #{userId} AND f.status = 1 AND u.role_id <> 0 "
            + "AND (u.status <> 1 OR u.exp_time <= #{now} OR u.flow <= 0 "
            + "OR u.in_flow + u.out_flow >= u.flow * #{bytesPerGb})")
    int enqueueBlockedUserForwards(@Param("userId") Long userId,
                                   @Param("now") Long now,
                                   @Param("bytesPerGb") Long bytesPerGb);

    @Insert("INSERT IGNORE INTO forward_pause_task "
            + "(forward_id, task_status, attempts, last_error, created_time, updated_time, status) "
            + "SELECT f.id, 'pending', 0, NULL, #{now}, #{now}, 1 "
            + "FROM forward f INNER JOIN user_tunnel ut ON ut.id = #{userTunnelId} "
            + "AND ut.user_id = f.user_id AND ut.tunnel_id = f.tunnel_id "
            + "WHERE f.user_id = #{userId} AND f.status = 1 "
            + "AND (ut.status <> 1 OR ut.exp_time <= #{now} OR ut.flow <= 0 "
            + "OR ut.in_flow + ut.out_flow >= ut.flow * #{bytesPerGb})")
    int enqueueBlockedUserTunnelForwards(@Param("userId") Long userId,
                                         @Param("userTunnelId") Integer userTunnelId,
                                         @Param("now") Long now,
                                         @Param("bytesPerGb") Long bytesPerGb);

    @Update("UPDATE forward_pause_task SET task_status = 'running', updated_time = #{now} "
            + "WHERE id = #{taskId} AND task_status = 'pending' AND status = 1")
    int claim(@Param("taskId") Long taskId, @Param("now") Long now);

    @Update("UPDATE forward_pause_task SET task_status = 'pending', attempts = attempts + 1, "
            + "last_error = #{message}, updated_time = #{now} WHERE id = #{taskId} AND status = 1")
    int markPending(@Param("taskId") Long taskId, @Param("message") String message, @Param("now") Long now);

    @Update("UPDATE forward_pause_task SET task_status = 'pending', updated_time = #{now} "
            + "WHERE task_status = 'running' AND updated_time < #{cutoff} AND status = 1")
    int requeueStaleRunning(@Param("cutoff") Long cutoff, @Param("now") Long now);
}

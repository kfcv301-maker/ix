package com.admin.mapper;

import com.admin.common.dto.ForwardSyncSummary;
import com.admin.entity.ForwardSyncTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Explicit state transitions keep node commands idempotent across retries. */
public interface ForwardSyncTaskMapper extends BaseMapper<ForwardSyncTask> {

    @Select("SELECT * FROM forward_sync_task WHERE status = 1 AND task_status = 'pending' "
            + "AND next_retry_time <= #{now} ORDER BY next_retry_time ASC, id ASC LIMIT #{limit}")
    List<ForwardSyncTask> selectReady(@Param("now") Long now, @Param("limit") int limit);

    @Select("SELECT * FROM forward_sync_task WHERE status = 1 AND node_id = #{nodeId} "
            + "AND task_status = 'pending' AND next_retry_time <= #{now} "
            + "ORDER BY next_retry_time ASC, id ASC LIMIT #{limit}")
    List<ForwardSyncTask> selectReadyForNode(@Param("nodeId") Long nodeId,
                                             @Param("now") Long now,
                                             @Param("limit") int limit);

    @Update("UPDATE forward_sync_task SET task_status = 'running', updated_time = #{now} "
            + "WHERE id = #{taskId} AND status = 1 AND task_status = 'pending' "
            + "AND next_retry_time <= #{now}")
    int claim(@Param("taskId") Long taskId, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET task_status = 'succeeded', last_error = NULL, "
            + "updated_time = #{now} WHERE id = #{taskId} AND status = 1 AND task_status = 'running'")
    int markSucceeded(@Param("taskId") Long taskId, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET task_status = 'pending', attempts = attempts + 1, "
            + "last_error = #{message}, next_retry_time = #{nextRetryTime}, updated_time = #{now} "
            + "WHERE id = #{taskId} AND status = 1 AND task_status = 'running'")
    int markRetry(@Param("taskId") Long taskId, @Param("message") String message,
                  @Param("nextRetryTime") Long nextRetryTime, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET task_status = 'cancelled', updated_time = #{now} "
            + "WHERE forward_id = #{forwardId} AND status = 1 "
            + "AND task_status <> 'cancelled'")
    int cancelOperationsForForward(@Param("forwardId") Long forwardId, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET task_status = 'cancelled', updated_time = #{now} "
            + "WHERE id = #{taskId} AND status = 1 AND task_status = 'running'")
    int markCancelled(@Param("taskId") Long taskId, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET task_status = 'pending', next_retry_time = #{now}, "
            + "updated_time = #{now} WHERE status = 1 AND task_status = 'running' "
            + "AND updated_time < #{cutoff}")
    int requeueStaleRunning(@Param("cutoff") Long cutoff, @Param("now") Long now);

    @Update("UPDATE forward_sync_task SET next_retry_time = #{now}, updated_time = #{now} "
            + "WHERE node_id = #{nodeId} AND status = 1 AND task_status = 'pending' "
            + "AND next_retry_time > #{now}")
    int makeReadyForNode(@Param("nodeId") Long nodeId, @Param("now") Long now);

    @Select("SELECT * FROM forward_sync_task WHERE operation_id = #{operationId} AND status = 1 ORDER BY id ASC")
    List<ForwardSyncTask> selectByOperationId(@Param("operationId") String operationId);

    @Select("<script>SELECT forward_id AS forwardId, operation_id AS operationId, MIN(operation) AS operation, "
            + "COUNT(*) AS totalTasks, "
            + "SUM(CASE WHEN task_status = 'pending' THEN 1 ELSE 0 END) AS pendingTasks, "
            + "SUM(CASE WHEN task_status = 'running' THEN 1 ELSE 0 END) AS runningTasks, "
            + "SUM(CASE WHEN attempts > 0 AND task_status != 'succeeded' THEN 1 ELSE 0 END) AS retriedTasks, "
            + "MAX(last_error) AS lastError, MAX(updated_time) AS latestTime "
            + "FROM forward_sync_task WHERE status = 1 AND task_status IN ('pending', 'running') "
            + "AND forward_id IN <foreach collection='forwardIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY forward_id, operation_id</script>")
    List<ForwardSyncSummary> selectActiveSummaries(@Param("forwardIds") List<Long> forwardIds);

    @Select("SELECT operation_id FROM forward_sync_task t INNER JOIN forward f ON f.id = t.forward_id "
            + "WHERE t.status = 1 AND t.operation = 'delete' AND f.status = 3 "
            + "GROUP BY t.operation_id HAVING SUM(CASE WHEN t.task_status = 'succeeded' THEN 0 ELSE 1 END) = 0 "
            + "AND MAX(t.updated_time) <= #{cutoff} LIMIT #{limit}")
    List<String> selectCompletedDeleteOperationIds(@Param("cutoff") Long cutoff, @Param("limit") int limit);

    @Delete("DELETE FROM forward_sync_task WHERE operation_id = #{operationId}")
    int deleteByOperationId(@Param("operationId") String operationId);

    @Delete("DELETE FROM forward_sync_task WHERE task_status IN ('succeeded', 'cancelled') "
            + "AND updated_time < #{cutoff}")
    int deleteCompletedOlderThan(@Param("cutoff") Long cutoff);
}

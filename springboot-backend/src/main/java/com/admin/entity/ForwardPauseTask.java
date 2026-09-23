package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Persisted retry work for a quota or expiry driven service pause. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("forward_pause_task")
public class ForwardPauseTask extends BaseEntity {

    private Long forwardId;

    /** pending or running */
    private String taskStatus;

    private Integer attempts;

    private String lastError;
}

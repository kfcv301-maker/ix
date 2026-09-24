package com.admin.common.task;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/** Runs a claimed endpoint command away from request and accounting threads. */
@Service
public class ForwardSyncTaskExecutor {

    @Resource
    private ForwardSyncTaskService forwardSyncTaskService;

    @Async("forwardSyncExecutor")
    public void execute(Long taskId) {
        forwardSyncTaskService.executeClaimedTask(taskId);
    }
}

package com.admin.service.impl;

import com.admin.entity.VpsDeploymentTask;
import com.admin.mapper.VpsDeploymentTaskMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VpsDeploymentExecutorTest {
    private VpsDeploymentTask task(String state, long created, long started) {
        VpsDeploymentTask task = new VpsDeploymentTask();
        task.setId(10L); task.setStatus(1); task.setTaskStatus(state);
        task.setCreatedTime(created); task.setStartedTime(started); task.setOutputLog("");
        return task;
    }
    private VpsDeploymentExecutor executor(VpsDeploymentTaskMapper mapper) {
        VpsDeploymentExecutor result = new VpsDeploymentExecutor();
        ReflectionTestUtils.setField(result, "taskMapper", mapper);
        return result;
    }
    @Test void queueAgeDoesNotExpireANewlyStartedRun() {
        long now = System.currentTimeMillis();
        VpsDeploymentTaskMapper mapper = mock(VpsDeploymentTaskMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(task("running", now-41*60_000L, now-60_000L)));
        executor(mapper).expireStalledTasks();
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
    @Test void stalePendingTaskIsExpiredOnlyIfItHasNotStartedSinceSelection() {
        long now = System.currentTimeMillis();
        VpsDeploymentTaskMapper mapper = mock(VpsDeploymentTaskMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(task("pending", now-41*60_000L, now)));
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0); // worker won the race
        executor(mapper).expireStalledTasks();
        ArgumentCaptor<UpdateWrapper<VpsDeploymentTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSegment().contains("task_status"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("pending"));
        verify(mapper, never()).updateById(any(VpsDeploymentTask.class));
    }
    @Test void activeLocalSshExecutionIsNeverUnlockedByTheReaper() {
        long now = System.currentTimeMillis();
        VpsDeploymentTaskMapper mapper = mock(VpsDeploymentTaskMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(task("running", now-80*60_000L, now-41*60_000L)));
        VpsDeploymentExecutor executor = executor(mapper);
        ((Set<Long>) ReflectionTestUtils.getField(executor, "localExecutions")).add(10L);
        executor.expireStalledTasks();
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }
    @Test void normalCompletionHasATerminalStateGuard() {
        VpsDeploymentTaskMapper mapper = mock(VpsDeploymentTaskMapper.class);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0); // already terminal
        ReflectionTestUtils.invokeMethod(executor(mapper), "finish", task("running", 1, 2), "succeeded", "done");
        ArgumentCaptor<UpdateWrapper<VpsDeploymentTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSegment().contains("task_status ="));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("running"));
        verify(mapper, never()).updateById(any(VpsDeploymentTask.class));
    }
}

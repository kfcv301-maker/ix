package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.admin.service.ForwardService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserDisableForwardTest {
    @Test void disableIncludesActiveErrorAndSyncingWithoutTouchingPausedOrDeleting() {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        ForwardSyncTaskService proxy = mock(ForwardSyncTaskService.class);
        ForwardService forwards = mock(ForwardService.class);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "self", proxy);
        Forward active = new Forward(); active.setId(20L);
        Forward syncing = new Forward(); syncing.setId(21L);
        Forward error = new Forward(); error.setId(22L);
        when(forwards.list(any(QueryWrapper.class))).thenAnswer(call -> {
            QueryWrapper<Forward> query = call.getArgument(0);
            assertTrue(query.getSqlSegment().contains("status IN"));
            assertEquals(java.util.Set.of(7L, 1, 2, -1), new java.util.HashSet<>(query.getParamNameValuePairs().values()));
            return List.of(active, syncing, error);
        });
        when(proxy.queueSafetyPause(anyLong())).thenReturn(R.ok());
        service.pauseAllForUser(7L);
        for (long id : new long[]{20,21,22}) verify(proxy).queueSafetyPause(id);
        when(proxy.queueSafetyPause(21L)).thenReturn(R.err("queue unavailable"));
        assertThrows(IllegalStateException.class, () -> service.pauseAllForUser(7L));
    }

    @Test void administratorCannotResumeForADisabledOwner() {
        ForwardSyncTaskService service = new ForwardSyncTaskService();
        ForwardService forwards = mock(ForwardService.class);
        UserMapper users = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "userMapper", users);
        Forward forward = new Forward(); forward.setId(20L); forward.setUserId(7); forward.setStatus(0);
        when(forwards.getById(20L)).thenReturn(forward);
        User owner = new User(); owner.setStatus(0); owner.setRoleId(0);
        when(users.lockById(7L)).thenReturn(owner);
        assertNotEquals(0, service.beginOperation(20L, "resume", false).getCode());
        verify(forwards, never()).update(any(), any());
    }
}

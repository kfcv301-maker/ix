package com.admin.common.service;

import com.admin.common.constants.ForwardStatus;
import com.admin.common.lang.R;
import com.admin.common.task.ForwardSyncTaskService;
import com.admin.entity.*;
import com.admin.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ForwardQueuedCreationTest {
    final ForwardCreationService service = new ForwardCreationService();
    final UserMapper users = mock(UserMapper.class);
    final ForwardMapper forwards = mock(ForwardMapper.class);
    final NodeDeletionMapper lifecycle = mock(NodeDeletionMapper.class);
    final ForwardSyncTaskService tasks = mock(ForwardSyncTaskService.class);
    final ForwardPortReservationService ports = mock(ForwardPortReservationService.class);
    final UserTunnelMapper grants = mock(UserTunnelMapper.class);
    final User owner = new User();
    final Forward forward = new Forward();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "forwardMapper", forwards);
        ReflectionTestUtils.setField(service, "nodeDeletionMapper", lifecycle);
        ReflectionTestUtils.setField(service, "syncTasks", tasks);
        ReflectionTestUtils.setField(service, "reservations", ports);
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        owner.setId(7L); owner.setRoleId(0); owner.setStatus(1);
        when(users.lockById(7L)).thenReturn(owner);
        forward.setId(20L); forward.setUserId(7); forward.setTunnelId(11); forward.setInPort(18000);
        forward.setCreateRequestId("request-000000001"); forward.setCreateRequestHash("hash");
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setStatus(1);
        when(lifecycle.lockTunnel(11L)).thenReturn(tunnel);
    }

    @Test void replayDoesNotAllocatePortsOrDispatchAgain() {
        forward.setStatus(ForwardStatus.SYNCING);
        when(forwards.selectOne(any())).thenReturn(forward);
        R result = service.createQueued(forward);
        assertEquals(0, result.getCode());
        assertEquals(20L, ((java.util.Map<?, ?>) result.getData()).get("forwardId"));
        verifyNoInteractions(ports, tasks, lifecycle);
        verify(forwards, never()).insert(any(Forward.class));
        forward.setCreateRequestHash("other");
        Forward retry = new Forward(); retry.setUserId(7); retry.setCreateRequestId("request-000000001"); retry.setCreateRequestHash("hash");
        assertNotEquals(0, service.createQueued(retry).getCode());
    }

    @Test void successfulCreateStoresNonActiveRuleBeforeQueueing() {
        var lease = mock(ForwardPortReservationService.ReservationLease.class);
        when(lease.isSuccess()).thenReturn(true);
        when(ports.reserve(eq(forward), any())).thenReturn(lease);
        when(forwards.insert(forward)).thenAnswer(call -> { assertEquals(ForwardStatus.PAUSED, forward.getStatus()); return 1; });
        when(tasks.requestResume(20L)).thenReturn(R.ok());
        assertEquals(0, service.createQueued(forward).getCode());
        assertEquals(ForwardStatus.SYNCING, forward.getStatus());
        var order = inOrder(forwards, ports, tasks);
        order.verify(forwards).insert(forward);
        order.verify(ports).reserve(eq(forward), any());
        order.verify(tasks).requestResume(20L);
    }

    @Test void failedQueueThrowsToRollBackTheWholeTransaction() {
        var lease = mock(ForwardPortReservationService.ReservationLease.class);
        when(lease.isSuccess()).thenReturn(true);
        when(ports.reserve(eq(forward), any())).thenReturn(lease);
        when(forwards.insert(forward)).thenReturn(1);
        when(tasks.requestResume(20L)).thenReturn(R.err("queue unavailable"));
        var rejected = assertThrows(ForwardCreationService.Rejected.class, () -> service.createQueued(forward));
        assertFalse(rejected.isPortConflict());
    }

    @Test void concurrentNewIntentCannotExceedQuotaAfterLock() {
        owner.setRoleId(1); owner.setNum(1);
        UserTunnel grant = new UserTunnel(); grant.setNum(10);
        when(grants.selectOne(any())).thenReturn(grant);
        when(forwards.selectCount(any())).thenReturn(1L);
        assertThrows(ForwardCreationService.Rejected.class, () -> service.createQueued(forward));
        verify(forwards, never()).insert(any(Forward.class));
        verifyNoInteractions(ports, tasks);
    }
}

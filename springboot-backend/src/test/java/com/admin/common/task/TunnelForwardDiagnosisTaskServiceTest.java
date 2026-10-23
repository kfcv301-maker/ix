package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.TunnelForwardDiagnosisLimiter;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TunnelForwardDiagnosisTaskServiceTest {
    private final AtomicLong now = new AtomicLong(1_000L);
    private final Queue<Runnable> coordinators = new ArrayDeque<>();
    private final ForwardService forwards = mock(ForwardService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TunnelMapper tunnels = mock(TunnelMapper.class);
    private final UserTunnelMapper grants = mock(UserTunnelMapper.class);
    private final Tunnel tunnel = new Tunnel();
    private final User user = new User();
    private final UserTunnel grant = new UserTunnel();
    private TunnelForwardDiagnosisTaskService service;
    private String taskId;

    @BeforeEach void setup() {
        service = new TunnelForwardDiagnosisTaskService(now::get, 90_000L, 900_000L);
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "tunnelMapper", tunnels);
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "limiter", new TunnelForwardDiagnosisLimiter());
        ReflectionTestUtils.setField(service, "coordinatorExecutor", (TaskExecutor) coordinators::add);
        ReflectionTestUtils.setField(service, "probeExecutor", (TaskExecutor) Runnable::run);
        tunnel.setId(11L); tunnel.setName("Tunnel"); tunnel.setOwnerUserId(7L); tunnel.setStatus(1);
        user.setId(7L); user.setRoleId(1); user.setStatus(1);
        grant.setUserId(7); grant.setTunnelId(11); grant.setStatus(1);
        when(users.selectById(7)).thenReturn(user);
        when(tunnels.selectById(11L)).thenReturn(tunnel);
        when(grants.selectOne(any())).thenReturn(grant);
        when(forwards.getById(anyLong())).thenAnswer(call -> rule(call.getArgument(0)));
        when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
            assertTrue(call.<BooleanSupplier>getArgument(1).getAsBoolean());
            return successful();
        });
    }

    private Forward rule(long id) {
        Forward rule = new Forward(); rule.setId(id); rule.setUserId(7); rule.setTunnelId(11);
        rule.setName("Rule " + id); rule.setRemoteAddr("target.example:443"); return rule;
    }
    private List<Forward> rules(int count) { return LongStream.rangeClosed(1, count).mapToObj(this::rule).toList(); }
    private R successful() { return R.ok(Map.of("results", List.of(Map.of("success", true, "description", "TCP")))); }
    @SuppressWarnings("unchecked") private Map<String, Object> data(R response) {
        assertEquals(0, response.getCode(), response.getMsg()); return (Map<String, Object>) response.getData();
    }
    private Map<String, Object> start(int count) {
        Map<String, Object> initial = data(service.start(tunnel, 7, rules(count)));
        taskId = (String) initial.get("taskId"); return initial;
    }
    private Map<String, Object> snapshot() { return data(service.get(taskId, 7, false)); }

    @Test void twentyOneAndFortyFiveForwardsAutomaticallyAdvanceWithoutCrossBatchProbes() {
        for (int count : new int[]{21, 45}) {
            setup();
            List<Long> probed = new ArrayList<>();
            when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
                Forward forward = call.getArgument(0);
                int batch = (int) ((forward.getId() - 1) / 20 + 1);
                Map<String, Object> progress = snapshot();
                assertEquals(batch, progress.get("currentBatch"));
                assertEquals(batch - 1, progress.get("completedBatches"));
                assertEquals((batch - 1) * 20, ((Number) progress.get("completedForwards")).intValue() / 20 * 20);
                probed.add(forward.getId());
                return successful();
            });
            Map<String, Object> initial = start(count);
            assertEquals((count + 19) / 20, initial.get("totalBatches"));
            assertEquals("queued", initial.get("status"));
            coordinators.remove().run();
            Map<String, Object> result = snapshot();
            assertEquals("completed", result.get("status"));
            assertEquals(count, result.get("completedForwards"));
            assertEquals(count, result.get("successfulForwards"));
            assertEquals(0, result.get("skippedForwards"));
            assertEquals((count + 19) / 20, result.get("completedBatches"));
            assertEquals(LongStream.rangeClosed(1, count).boxed().toList(), probed);
        }
    }

    @Test void cancelledJobKeepsPartialResultsAndCannotAddLateSuccessesOrAdvanceBatches() {
        start(45);
        when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
            Forward rule = call.getArgument(0);
            if (rule.getId() == 5L) service.cancel(taskId, 7, false);
            return successful();
        });
        coordinators.remove().run();
        Map<String, Object> result = snapshot();
        assertEquals("cancelled", result.get("status"));
        assertEquals(4, result.get("completedForwards"));
        assertEquals(41, result.get("skippedForwards"));
        assertEquals(0, result.get("failedForwards"));
        assertEquals(1, result.get("currentBatch"));
        verify(forwards, times(5)).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
        assertEquals(result, data(service.cancel(taskId, 7, false)));
    }

    @Test void queuedCancellationInterruptsAndDiscardsPendingProbeWork() throws Exception {
        List<Runnable> probes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch scheduled = new CountDownLatch(2);
        ReflectionTestUtils.setField(service, "probeExecutor", (TaskExecutor) task -> { probes.add(task); scheduled.countDown(); });
        start(45);
        Thread coordinator = new Thread(coordinators.remove()); coordinator.start();
        try {
            assertTrue(scheduled.await(2, TimeUnit.SECONDS));
            assertEquals("cancelled", data(service.cancel(taskId, 7, false)).get("status"));
            coordinator.join(2_000L); assertFalse(coordinator.isAlive());
            probes.forEach(Runnable::run);
            verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
            assertEquals(45, snapshot().get("skippedForwards"));
        } finally { coordinator.interrupt(); coordinator.join(2_000L); }
    }

    @Test void aBatchTimeoutStopsLaterBatchesAndFreezesTheTimedOutSnapshot() {
        start(45);
        when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
            if (call.<Forward>getArgument(0).getId() == 3L) now.addAndGet(90_000L);
            return successful();
        });
        coordinators.remove().run();
        Map<String, Object> result = snapshot();
        assertEquals("timed_out", result.get("status"));
        assertEquals(1, result.get("currentBatch"));
        assertEquals(0, result.get("completedBatches"));
        assertEquals(2, result.get("completedForwards"));
        assertEquals(43, result.get("skippedForwards"));
        verify(forwards, times(3)).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void cancellingRunningWorkersInterruptsTheirPendingWaitsAndPreservesTwoProbeBound() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
        ReflectionTestUtils.setField(service, "probeExecutor", (TaskExecutor) task -> {
            Thread worker = new Thread(task); workers.add(worker); worker.start();
        });
        when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return successful(); }
            catch (InterruptedException exception) { interrupted.countDown(); return R.err("interrupted"); }
        });
        start(45);
        Thread coordinator = new Thread(coordinators.remove()); coordinator.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(2, workers.size());
            service.cancel(taskId, 7, false);
            coordinator.join(2_000L); assertFalse(coordinator.isAlive());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(2, workers.size());
            assertEquals("cancelled", snapshot().get("status"));
            assertEquals(45, snapshot().get("skippedForwards"));
        } finally {
            coordinator.interrupt(); coordinator.join(2_000L);
            for (Thread worker : workers) { worker.interrupt(); worker.join(2_000L); }
        }
    }

    @Test void totalDeadlineIsCappedAndIncludesTimeSpentWaitingForCoordinator() {
        Map<String, Object> initial = start(1001);
        assertEquals(now.get() + 900_000L, initial.get("deadlineAt"));
        now.addAndGet(900_001L);
        service.clearExpiredTasks();
        coordinators.remove().run();
        assertEquals("timed_out", snapshot().get("status"));
        assertEquals(1001, snapshot().get("skippedForwards"));
        verifyNoInteractions(forwards);
    }

    @Test void revokedGrantBetweenBatchesStopsSystemProbes() {
        start(45);
        when(forwards.diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class))).thenAnswer(call -> {
            Forward rule = call.getArgument(0);
            if (rule.getId() == 20L) grant.setStatus(0);
            return successful();
        });
        coordinators.remove().run();
        assertEquals("failed", snapshot().get("status"));
        assertEquals(20, snapshot().get("completedForwards"));
        assertEquals(25, snapshot().get("skippedForwards"));
        assertTrue(snapshot().get("message").toString().contains("权限"));
        verify(forwards, times(20)).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void roleDowngradeCannotContinueInspectingAnotherUsersForward() {
        user.setRoleId(0);
        Forward other = rule(1L); other.setUserId(8);
        taskId = (String) data(service.start(tunnel, 7, List.of(other))).get("taskId");
        user.setRoleId(1);
        when(forwards.getById(1L)).thenReturn(other);
        coordinators.remove().run();
        assertEquals("completed", snapshot().get("status"));
        assertEquals(1, snapshot().get("failedForwards"));
        verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void privateTunnelTransferStopsAllRemainingProbes() {
        start(21); tunnel.setOwnerUserId(8L); coordinators.remove().run();
        assertEquals("failed", snapshot().get("status"));
        assertEquals(21, snapshot().get("skippedForwards"));
        verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void disabledTunnelStopsAllRemainingProbesEvenWithAnActiveGrant() {
        start(21); tunnel.setStatus(0); coordinators.remove().run();
        assertEquals("failed", snapshot().get("status"));
        assertEquals(21, snapshot().get("skippedForwards"));
        verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void expiredAccountStopsAllRemainingProbes() {
        start(21); user.setExpTime(now.get()); coordinators.remove().run();
        assertEquals("failed", snapshot().get("status"));
        assertEquals(21, snapshot().get("skippedForwards"));
        verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }

    @Test void taskOwnershipAndEmptySelectionRemainSafe() {
        start(0);
        assertEquals("completed", snapshot().get("status"));
        assertEquals(0, snapshot().get("totalBatches"));
        assertEquals(0, snapshot().get("totalForwards"));
        assertEquals(403, service.get(taskId, 8, false).getCode());
        assertEquals(403, service.cancel(taskId, 8, false).getCode());
        assertEquals(0, service.get(taskId, 8, true).getCode());
        assertNotEquals(0, service.get(null, 7, false).getCode());
        verifyNoInteractions(forwards);
        assertTrue(coordinators.isEmpty());
    }

    @Test void selectionAndSnapshotsCannotBeMutatedByCallers() {
        Forward source = rule(1L);
        taskId = (String) data(service.start(tunnel, 7, Arrays.asList(source, source, null))).get("taskId");
        source.setName("Mutated"); source.setRemoteAddr("other.example:80");
        coordinators.remove().run();
        @SuppressWarnings("unchecked") List<Map<String, Object>> reports = (List<Map<String, Object>>) snapshot().get("forwards");
        assertEquals(1, reports.size());
        assertEquals("Rule 1", reports.getFirst().get("forwardName"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> checks = (List<Map<String, Object>>) reports.getFirst().get("results");
        checks.getFirst().put("success", false); reports.getFirst().put("success", false);
        assertEquals(1, snapshot().get("successfulForwards"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> again = (List<Map<String, Object>>) snapshot().get("forwards");
        @SuppressWarnings("unchecked") List<Map<String, Object>> againChecks = (List<Map<String, Object>>) again.getFirst().get("results");
        assertEquals(true, againChecks.getFirst().get("success"));
    }

    @Test void coordinatorAndProbeRejectionsReturnBoundedExplicitErrors() {
        ReflectionTestUtils.setField(service, "coordinatorExecutor", (TaskExecutor) task -> { throw new TaskRejectedException("full"); });
        assertEquals(429, service.start(tunnel, 7, rules(21)).getCode());
        tunnel.setId(12L);
        ReflectionTestUtils.setField(service, "coordinatorExecutor", (TaskExecutor) coordinators::add);
        ReflectionTestUtils.setField(service, "probeExecutor", (TaskExecutor) task -> { throw new TaskRejectedException("full"); });
        start(21); coordinators.remove().run();
        assertEquals("completed", snapshot().get("status"));
        assertEquals(21, snapshot().get("failedForwards"));
        verify(forwards, never()).diagnoseForwardForSystem(any(Forward.class), any(BooleanSupplier.class));
    }
}

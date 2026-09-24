package com.admin.common.task;

import com.admin.common.constants.ForwardStatus;
import com.admin.common.dto.ForwardSyncSummary;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.service.ForwardPortReservationService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.Forward;
import com.admin.entity.ForwardSyncTask;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardSyncTaskMapper;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persists one command per ingress/egress endpoint before a GOST command is
 * sent.  A forwarding record therefore never disappears, or claims to be
 * safely paused/resumed, while a subset of its nodes still disagrees.
 */
@Slf4j
@Service
public class ForwardSyncTaskService {

    private static final String ENDPOINT_INGRESS = "ingress";
    private static final String ENDPOINT_EGRESS = "egress";
    private static final String OPERATION_PAUSE = "pause";
    private static final String OPERATION_RESUME = "resume";
    private static final String OPERATION_DELETE = "delete";
    private static final String TASK_PENDING = "pending";
    private static final String TASK_RUNNING = "running";
    private static final String TASK_SUCCEEDED = "succeeded";

    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int RETRY_BATCH_SIZE = 128;
    private static final long STALE_RUNNING_MILLIS = 2 * 60 * 1000L;
    private static final long DELETE_SETTLE_MILLIS = 30 * 1000L;
    private static final long PAUSE_FLOW_GRACE_MILLIS = 30 * 1000L;
    private static final long COMPLETED_TASK_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000L;

    @Resource
    private ForwardSyncTaskMapper forwardSyncTaskMapper;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    private TunnelService tunnelService;

    @Resource
    private UserService userService;

    @Resource
    private UserTunnelService userTunnelService;

    @Resource
    private TunnelIngressNodeResolver ingressNodeResolver;

    @Resource
    @Lazy
    private ForwardSyncTaskExecutor forwardSyncTaskExecutor;

    @Resource
    private ForwardPortReservationService forwardPortReservationService;

    /** Proxy reference used when a completion must start a new transaction. */
    @Resource
    @Lazy
    private ForwardSyncTaskService self;

    public R requestPause(Long forwardId) {
        return beginOperation(forwardId, OPERATION_PAUSE, false);
    }

    public R requestResume(Long forwardId) {
        return beginOperation(forwardId, OPERATION_RESUME, false);
    }

    public R requestDelete(Long forwardId) {
        return beginOperation(forwardId, OPERATION_DELETE, false);
    }

    /**
     * Entitlement enforcement may supersede a half-finished manual resume.
     * It never marks the forward paused until every endpoint has acknowledged
     * the compensating pause command.
     */
    public R queueSafetyPause(Long forwardId) {
        return beginOperation(forwardId, OPERATION_PAUSE, true);
    }

    @Transactional
    public R beginOperation(Long forwardId, String operation, boolean supersede) {
        if (forwardId == null) {
            return R.err("转发不存在");
        }
        Forward forward = forwardService.getById(forwardId);
        if (forward == null) {
            return R.err("转发不存在");
        }
        if (Objects.equals(forward.getStatus(), ForwardStatus.DELETING)) {
            return OPERATION_DELETE.equals(operation)
                    ? queued("删除同步仍在进行中", null, 0)
                    : R.err("转发正在删除同步中，暂不能修改状态");
        }
        if (Objects.equals(forward.getStatus(), ForwardStatus.SYNCING)) {
            if (!supersede) {
                return R.err("转发正在与节点同步，请等待当前操作完成");
            }
            // Cancel completed rows as well: an in-flight resume must not
            // finalise to ACTIVE after the safety pause has been requested.
            forwardSyncTaskMapper.cancelOperationsForForward(forwardId, System.currentTimeMillis());
        }

        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在，无法同步节点配置");
        }
        if (OPERATION_RESUME.equals(operation)) {
            String denied = resumeDeniedReason(forward, tunnel);
            if (denied != null) {
                return R.err(denied);
            }
        }

        Set<Long> ingressNodeIds = new LinkedHashSet<>(ingressNodeResolver.resolveNodeIds(tunnel));
        if (ingressNodeIds.isEmpty()) {
            return R.err("隧道没有可同步的入口节点");
        }
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD) && tunnel.getOutNodeId() == null) {
            return R.err("隧道没有可同步的出口节点");
        }

        String serviceName = serviceName(forward);
        String operationId = UUID.randomUUID().toString();
        int targetStatus = OPERATION_PAUSE.equals(operation) ? ForwardStatus.PAUSED : ForwardStatus.ACTIVE;
        int transientStatus = OPERATION_DELETE.equals(operation) ? ForwardStatus.DELETING : ForwardStatus.SYNCING;
        long now = System.currentTimeMillis();

        UpdateWrapper<Forward> update = new UpdateWrapper<>();
        update.eq("id", forwardId)
                .set("status", transientStatus)
                .set("updated_time", now);
        if (OPERATION_RESUME.equals(operation)) {
            update.set("flow_grace_until", null);
        }
        if (!forwardService.update(null, update)) {
            return R.err("更新转发同步状态失败");
        }

        List<ForwardSyncTask> tasks = new ArrayList<>();
        for (Long nodeId : ingressNodeIds) {
            tasks.add(newTask(operationId, forward, nodeId, ENDPOINT_INGRESS, operation,
                    serviceName, targetStatus, now));
        }
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
            tasks.add(newTask(operationId, forward, tunnel.getOutNodeId(), ENDPOINT_EGRESS, operation,
                    serviceName, targetStatus, now));
        }
        for (ForwardSyncTask task : tasks) {
            if (forwardSyncTaskMapper.insert(task) != 1) {
                throw new IllegalStateException("保存节点同步任务失败");
            }
        }

        runAfterCommit(() -> dispatchReadyForOperation(operationId));
        String message = OPERATION_DELETE.equals(operation)
                ? "已进入删除同步队列，等待所有节点确认清理"
                : "已进入节点同步队列";
        return queued(message, operationId, tasks.size());
    }

    /** Called only by the bounded executor after a database claim. */
    public void executeClaimedTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        ForwardSyncTask task = forwardSyncTaskMapper.selectById(taskId);
        if (task == null || !TASK_RUNNING.equals(task.getTaskStatus())) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            Forward forward = forwardService.getById(task.getForwardId());
            if (forward == null) {
                forwardSyncTaskMapper.markSucceeded(task.getId(), now);
                return;
            }
            if (!isStillDesired(task, forward)) {
                forwardSyncTaskMapper.markCancelled(task.getId(), now);
                return;
            }

            String failure = executeEndpoint(task, forward);
            if (failure == null) {
                if (forwardSyncTaskMapper.markSucceeded(task.getId(), now) == 1) {
                    finalizeIfComplete(task.getOperationId());
                } else {
                    compensateCancelledCommand(task, forward);
                }
                return;
            }
            forwardSyncTaskMapper.markRetry(task.getId(), failure, nextRetryTime(task, now), now);
        } catch (Exception exception) {
            String error = safeMessage(exception.getMessage(), exception.getClass().getSimpleName());
            log.warn("转发节点同步任务 {} 失败: {}", taskId, error);
            forwardSyncTaskMapper.markRetry(taskId, error, nextRetryTime(task, now), now);
        }
    }

    /** Retry ready tasks promptly when a node sends a new inventory report. */
    public void retryPendingForNode(Long nodeId) {
        if (nodeId == null) return;
        long now = System.currentTimeMillis();
        forwardSyncTaskMapper.makeReadyForNode(nodeId, now);
        dispatch(forwardSyncTaskMapper.selectReadyForNode(nodeId, now, RETRY_BATCH_SIZE));
    }

    @Scheduled(fixedDelay = 15_000L)
    public void retryPending() {
        long now = System.currentTimeMillis();
        forwardSyncTaskMapper.requeueStaleRunning(now - STALE_RUNNING_MILLIS, now);
        dispatch(forwardSyncTaskMapper.selectReady(now, RETRY_BATCH_SIZE));
    }

    /** Keep the record briefly after all delete ACKs for sequenced tail flow. */
    @Scheduled(fixedDelay = 10_000L)
    public void finalizeCompletedDeletes() {
        List<String> operationIds = forwardSyncTaskMapper.selectCompletedDeleteOperationIds(
                System.currentTimeMillis() - DELETE_SETTLE_MILLIS, RETRY_BATCH_SIZE);
        for (String operationId : operationIds) {
            finalizeDelete(operationId);
        }
    }

    @Scheduled(cron = "0 45 3 * * ?")
    public void purgeCompletedTasks() {
        forwardSyncTaskMapper.deleteCompletedOlderThan(System.currentTimeMillis() - COMPLETED_TASK_RETENTION_MILLIS);
    }

    /** Only active operations are included; completed history is kept out of list responses. */
    public Map<Long, ForwardSyncSummary> activeSummaries(Collection<Long> forwardIds) {
        if (forwardIds == null || forwardIds.isEmpty()) return Collections.emptyMap();
        Set<Long> wanted = new LinkedHashSet<>(forwardIds);
        Map<Long, ForwardSyncSummary> result = new LinkedHashMap<>();
        for (ForwardSyncSummary summary : forwardSyncTaskMapper.selectActiveSummaries()) {
            if (summary.getForwardId() == null || !wanted.contains(summary.getForwardId())) continue;
            ForwardSyncSummary existing = result.get(summary.getForwardId());
            if (existing == null || value(summary.getLatestTime()) > value(existing.getLatestTime())) {
                result.put(summary.getForwardId(), summary);
            }
        }
        return result;
    }

    private void dispatchReadyForOperation(String operationId) {
        if (operationId == null) return;
        long now = System.currentTimeMillis();
        List<ForwardSyncTask> ready = new ArrayList<>();
        for (ForwardSyncTask task : forwardSyncTaskMapper.selectByOperationId(operationId)) {
            if (TASK_PENDING.equals(task.getTaskStatus()) && value(task.getNextRetryTime()) <= now) {
                ready.add(task);
            }
        }
        dispatch(ready);
    }

    private void dispatch(List<ForwardSyncTask> tasks) {
        if (tasks == null) return;
        for (ForwardSyncTask task : tasks) {
            if (task == null || task.getId() == null) continue;
            long now = System.currentTimeMillis();
            if (forwardSyncTaskMapper.claim(task.getId(), now) != 1) continue;
            try {
                forwardSyncTaskExecutor.execute(task.getId());
            } catch (TaskRejectedException exception) {
                forwardSyncTaskMapper.markRetry(task.getId(), "节点同步执行器繁忙", now + 5_000L, now);
            } catch (RuntimeException exception) {
                forwardSyncTaskMapper.markRetry(task.getId(), "节点同步任务调度失败", now + 5_000L, now);
            }
        }
    }

    private String executeEndpoint(ForwardSyncTask task, Forward forward) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) return "隧道不存在";
        if (task.getNodeId() == null) return "节点不存在";

        if (ENDPOINT_INGRESS.equals(task.getEndpoint())) {
            return executeIngress(task, forward, tunnel);
        }
        if (ENDPOINT_EGRESS.equals(task.getEndpoint())) {
            if (!Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) return null;
            return executeEgress(task, forward, tunnel);
        }
        return "未知同步节点类型";
    }

    private String executeIngress(ForwardSyncTask task, Forward forward, Tunnel tunnel) {
        GostDto result;
        if (OPERATION_PAUSE.equals(task.getOperation())) {
            result = GostUtil.PauseService(task.getNodeId(), task.getServiceName());
            return successOrAlreadyAbsent(result) ? null : endpointError("暂停入口服务", result);
        }
        if (OPERATION_DELETE.equals(task.getOperation())) {
            result = GostUtil.DeleteService(task.getNodeId(), task.getServiceName());
            if (!successOrAlreadyAbsent(result)) return endpointError("删除入口服务", result);
            if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
                result = GostUtil.DeleteChains(task.getNodeId(), task.getServiceName());
                if (!successOrAlreadyAbsent(result)) return endpointError("删除入口链", result);
            }
            return null;
        }

        result = GostUtil.ResumeService(task.getNodeId(), task.getServiceName());
        if (isSuccess(result)) return null;
        if (!isNotFound(result)) return endpointError("恢复入口服务", result);
        return recreateIngress(task, forward, tunnel);
    }

    private String executeEgress(ForwardSyncTask task, Forward forward, Tunnel tunnel) {
        GostDto result;
        if (OPERATION_PAUSE.equals(task.getOperation())) {
            result = GostUtil.PauseRemoteService(task.getNodeId(), task.getServiceName());
            return successOrAlreadyAbsent(result) ? null : endpointError("暂停出口服务", result);
        }
        if (OPERATION_DELETE.equals(task.getOperation())) {
            result = GostUtil.DeleteRemoteService(task.getNodeId(), task.getServiceName());
            return successOrAlreadyAbsent(result) ? null : endpointError("删除出口服务", result);
        }
        result = GostUtil.ResumeRemoteService(task.getNodeId(), task.getServiceName());
        if (isSuccess(result)) return null;
        if (!isNotFound(result)) return endpointError("恢复出口服务", result);
        result = GostUtil.AddRemoteService(task.getNodeId(), task.getServiceName(), forward.getOutPort(),
                forward.getRemoteAddr(), tunnel.getProtocol(), forward.getStrategy(), tunnel.getInterfaceName());
        return isSuccess(result) ? null : endpointError("重建出口服务", result);
    }

    private String recreateIngress(ForwardSyncTask task, Forward forward, Tunnel tunnel) {
        if (Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)) {
            String remoteAddress = formatAddress(tunnel.getOutIp(), forward.getOutPort());
            GostDto chainResult = GostUtil.UpdateChains(task.getNodeId(), task.getServiceName(), remoteAddress,
                    tunnel.getProtocol(), tunnel.getInterfaceName());
            if (isNotFound(chainResult)) {
                chainResult = GostUtil.AddChains(task.getNodeId(), task.getServiceName(), remoteAddress,
                        tunnel.getProtocol(), tunnel.getInterfaceName());
            }
            if (!isSuccess(chainResult)) return endpointError("重建入口链", chainResult);
        }
        UserTunnel userTunnel = findUserTunnel(forward);
        Integer limiter = userTunnel == null ? null : userTunnel.getSpeedId();
        String interfaceName = Objects.equals(tunnel.getType(), TUNNEL_TYPE_TUNNEL_FORWARD)
                ? null : forward.getInterfaceName();
        GostDto serviceResult = GostUtil.AddService(task.getNodeId(), task.getServiceName(), forward.getInPort(),
                limiter, forward.getRemoteAddr(), tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
        return isSuccess(serviceResult) ? null : endpointError("重建入口服务", serviceResult);
    }

    private void finalizeIfComplete(String operationId) {
        List<ForwardSyncTask> tasks = forwardSyncTaskMapper.selectByOperationId(operationId);
        if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !TASK_SUCCEEDED.equals(task.getTaskStatus()))) {
            return;
        }
        ForwardSyncTask representative = tasks.get(0);
        if (OPERATION_DELETE.equals(representative.getOperation())) {
            // The scheduled finalizer provides a short tail-report window.
            return;
        }
        Forward forward = forwardService.getById(representative.getForwardId());
        if (forward == null || !Objects.equals(forward.getStatus(), ForwardStatus.SYNCING)) return;
        if (OPERATION_RESUME.equals(representative.getOperation())) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            String denied = tunnel == null ? "隧道不存在" : resumeDeniedReason(forward, tunnel);
            if (denied != null) {
                log.warn("转发 {} 同步恢复后不再符合运行条件: {}，将补偿暂停", forward.getId(), denied);
                self.queueSafetyPause(forward.getId());
                return;
            }
        }

        long now = System.currentTimeMillis();
        UpdateWrapper<Forward> update = new UpdateWrapper<>();
        update.eq("id", forward.getId()).eq("status", ForwardStatus.SYNCING)
                .set("status", representative.getTargetStatus()).set("updated_time", now);
        if (Objects.equals(representative.getTargetStatus(), ForwardStatus.PAUSED)) {
            update.set("flow_grace_until", now + PAUSE_FLOW_GRACE_MILLIS);
        } else {
            update.set("flow_grace_until", null);
        }
        forwardService.update(null, update);
    }

    private void finalizeDelete(String operationId) {
        List<ForwardSyncTask> tasks = forwardSyncTaskMapper.selectByOperationId(operationId);
        if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !TASK_SUCCEEDED.equals(task.getTaskStatus()))) return;
        ForwardSyncTask representative = tasks.get(0);
        Forward forward = forwardService.getById(representative.getForwardId());
        if (forward == null) {
            forwardSyncTaskMapper.deleteByOperationId(operationId);
            return;
        }
        if (!Objects.equals(forward.getStatus(), ForwardStatus.DELETING)) return;
        if (forwardService.removeById(forward.getId())) {
            // Release only after every endpoint has acknowledged deletion and
            // the tail-flow grace window has elapsed.
            forwardPortReservationService.releaseAll(forward.getId());
            forwardSyncTaskMapper.deleteByOperationId(operationId);
        }
    }

    private boolean isStillDesired(ForwardSyncTask task, Forward forward) {
        return OPERATION_DELETE.equals(task.getOperation())
                ? Objects.equals(forward.getStatus(), ForwardStatus.DELETING)
                : Objects.equals(forward.getStatus(), ForwardStatus.SYNCING);
    }

    /**
     * A resume command may finish just after entitlement enforcement cancelled
     * it. Re-queueing the compensating pause ensures that such a late node ACK
     * can never leave the database claiming a safely paused service.
     */
    private void compensateCancelledCommand(ForwardSyncTask task, Forward forward) {
        if (!OPERATION_RESUME.equals(task.getOperation()) || forward == null
                || !Objects.equals(forward.getStatus(), ForwardStatus.SYNCING)) {
            return;
        }
        log.warn("转发 {} 的已取消恢复命令在节点上晚到，重新下发安全暂停", forward.getId());
        self.queueSafetyPause(forward.getId());
    }

    private String resumeDeniedReason(Forward forward, Tunnel tunnel) {
        if (!Objects.equals(tunnel.getStatus(), ForwardStatus.ACTIVE)) return "隧道已禁用，无法恢复服务";
        User owner = userService.getById(forward.getUserId());
        if (owner == null) return "转发归属用户不存在";
        if (Objects.equals(owner.getRoleId(), 0)) return null;
        long now = System.currentTimeMillis();
        if (!Objects.equals(owner.getStatus(), 1)) return "转发所属用户已禁用";
        if (owner.getExpTime() != null && owner.getExpTime() <= now) return "转发所属用户已到期";
        if (!hasRemainingQuota(owner.getFlow(), owner.getInFlow(), owner.getOutFlow())) return "转发所属用户流量已用完";

        UserTunnel userTunnel = findUserTunnel(forward);
        if (userTunnel == null) return "转发所属用户没有该隧道权限";
        if (!Objects.equals(userTunnel.getStatus(), 1)) return "转发所属用户的隧道权限已禁用";
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= now) return "转发所属用户的隧道权限已到期";
        return hasRemainingQuota(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow())
                ? null : "转发所属用户的隧道流量已用完";
    }

    private boolean hasRemainingQuota(Long quotaGb, Long inbound, Long outbound) {
        if (quotaGb == null || quotaGb <= 0) return false;
        try {
            long used = Math.addExact(inbound == null ? 0 : inbound, outbound == null ? 0 : outbound);
            return used < Math.multiplyExact(quotaGb, 1024L * 1024 * 1024L);
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private ForwardSyncTask newTask(String operationId, Forward forward, Long nodeId, String endpoint,
                                    String operation, String serviceName, int targetStatus, long now) {
        ForwardSyncTask task = new ForwardSyncTask();
        task.setOperationId(operationId);
        task.setForwardId(forward.getId());
        task.setNodeId(nodeId);
        task.setEndpoint(endpoint);
        task.setOperation(operation);
        task.setServiceName(serviceName);
        task.setTargetStatus(targetStatus);
        task.setTaskStatus(TASK_PENDING);
        task.setAttempts(0);
        task.setNextRetryTime(now);
        task.setCreatedTime(now);
        task.setUpdatedTime(now);
        task.setStatus(1);
        return task;
    }

    private UserTunnel findUserTunnel(Forward forward) {
        if (forward == null || forward.getUserId() == null || forward.getTunnelId() == null) return null;
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()), false);
    }

    private String serviceName(Forward forward) {
        UserTunnel userTunnel = findUserTunnel(forward);
        int userTunnelId = userTunnel == null || userTunnel.getId() == null ? 0 : userTunnel.getId();
        return forward.getId() + "_" + forward.getUserId() + "_" + userTunnelId;
    }

    private long nextRetryTime(ForwardSyncTask task, long now) {
        int attempts = (task == null || task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
        int exponent = Math.min(6, Math.max(0, attempts - 1));
        long delay = Math.min(5 * 60 * 1000L, 5_000L * (1L << exponent));
        return now + delay;
    }

    private boolean isSuccess(GostDto result) {
        return result != null && "OK".equalsIgnoreCase(result.getMsg());
    }

    private boolean successOrAlreadyAbsent(GostDto result) {
        return isSuccess(result) || isNotFound(result);
    }

    private boolean isNotFound(GostDto result) {
        return result != null && result.getMsg() != null
                && result.getMsg().toLowerCase().contains("not found");
    }

    private String endpointError(String action, GostDto result) {
        return action + "失败：" + safeMessage(result == null ? null : result.getMsg(), "无响应");
    }

    private String formatAddress(String host, Integer port) {
        if (host == null || port == null) return "";
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private String safeMessage(String value, String fallback) {
        String message = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        if (message.isEmpty()) message = fallback;
        return message.length() > 480 ? message.substring(0, 480) : message;
    }

    private R queued(String message, String operationId, int endpointCount) {
        R result = R.ok();
        result.setMsg(message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operationId", operationId);
        data.put("endpointCount", endpointCount);
        result.setData(data);
        return result;
    }

    private void runAfterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }
}

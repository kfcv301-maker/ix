package com.admin.common.task;

import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPauseTask;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardPauseTaskMapper;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Objects;

/** Queues durable pause work and periodically retries only unresolved tasks. */
@Slf4j
@Service
public class ForwardPauseTaskService {

    private static final long BYTES_TO_GB = 1024L * 1024 * 1024L;
    private static final long STALE_RUNNING_MILLIS = 2 * 60 * 1000L;
    private static final int RETRY_BATCH_SIZE = 100;

    @Resource
    private ForwardPauseTaskMapper forwardPauseTaskMapper;

    @Resource
    private ForwardPauseTaskExecutor forwardPauseTaskExecutor;

    @Resource
    private ForwardService forwardService;

    @Resource
    private UserService userService;

    @Resource
    private UserTunnelService userTunnelService;

    @Resource
    private TunnelService tunnelService;

    @Resource
    private TunnelIngressNodeResolver ingressNodeResolver;

    /** Called after a manual entitlement change; it never waits on a node. */
    public void enqueueForCurrentLimits(Long userId, Long userTunnelId) {
        User user = userId == null ? null : userService.getById(userId);
        if (user == null || Objects.equals(user.getRoleId(), 0)) {
            return;
        }
        if (isUserBlocked(user)) {
            enqueueForwards(new QueryWrapper<Forward>().eq("user_id", user.getId()).eq("status", 1));
            return;
        }
        if (userTunnelId == null) {
            return;
        }
        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel != null && isUserTunnelBlocked(userTunnel)) {
            enqueueForwards(new QueryWrapper<Forward>().eq("user_id", user.getId())
                    .eq("tunnel_id", userTunnel.getTunnelId()).eq("status", 1));
        }
    }

    /** Persist and dispatch pause work for one already-known forward. */
    public void enqueue(Long forwardId) {
        if (forwardId == null) return;
        long now = System.currentTimeMillis();
        forwardPauseTaskMapper.insertIfAbsent(forwardId, now);
        ForwardPauseTask task = forwardPauseTaskMapper.selectOne(new QueryWrapper<ForwardPauseTask>()
                .eq("forward_id", forwardId).eq("status", 1));
        if (task != null) {
            dispatch(task.getId());
        }
    }

    /**
     * Expiry handling changes the entitlement and creates its compensation
     * work in one transaction. A process stop between those two writes can no
     * longer leave a disabled account with live services and no retry record.
     */
    @Transactional
    public int disableExpiredUserAndQueue(Long userId) {
        if (userId == null) return 0;
        long now = System.currentTimeMillis();
        UpdateWrapper<User> disable = new UpdateWrapper<>();
        disable.eq("id", userId).eq("status", 1)
                .set("status", 0).set("updated_time", now);
        if (!userService.update(null, disable)) {
            return 0;
        }
        return forwardPauseTaskMapper.enqueueBlockedUserForwards(userId, now, BYTES_TO_GB);
    }

    @Transactional
    public int disableExpiredUserTunnelAndQueue(UserTunnel userTunnel) {
        if (userTunnel == null || userTunnel.getId() == null || userTunnel.getUserId() == null) return 0;
        long now = System.currentTimeMillis();
        UpdateWrapper<UserTunnel> disable = new UpdateWrapper<>();
        disable.eq("id", userTunnel.getId()).eq("status", 1).set("status", 0);
        if (!userTunnelService.update(null, disable)) {
            return 0;
        }
        return forwardPauseTaskMapper.enqueueBlockedUserTunnelForwards(userTunnel.getUserId().longValue(),
                userTunnel.getId(), now, BYTES_TO_GB);
    }

    /** A just-reconnected node should not wait for the periodic retry sweep. */
    public void retryPendingForNode(Long nodeId) {
        if (nodeId == null) return;
        List<ForwardPauseTask> tasks = forwardPauseTaskMapper.selectList(new QueryWrapper<ForwardPauseTask>()
                .eq("status", 1).eq("task_status", "pending").last("LIMIT " + RETRY_BATCH_SIZE));
        for (ForwardPauseTask task : tasks) {
            Forward forward = forwardService.getById(task.getForwardId());
            Tunnel tunnel = forward == null ? null : tunnelService.getById(forward.getTunnelId());
            if (tunnel != null && (ingressNodeResolver.isIngressNode(tunnel, nodeId)
                    || Objects.equals(tunnel.getOutNodeId(), nodeId))) {
                dispatch(task.getId());
            }
        }
    }

    @Scheduled(fixedDelay = 15_000L)
    public void retryPending() {
        long now = System.currentTimeMillis();
        forwardPauseTaskMapper.requeueStaleRunning(now - STALE_RUNNING_MILLIS, now);
        List<ForwardPauseTask> tasks = forwardPauseTaskMapper.selectList(new QueryWrapper<ForwardPauseTask>()
                .eq("status", 1).eq("task_status", "pending").last("LIMIT " + RETRY_BATCH_SIZE));
        for (ForwardPauseTask task : tasks) {
            dispatch(task.getId());
        }
    }

    /**
     * Starts newly committed quota work without re-reading the entitlement
     * graph on every ordinary traffic report. Existing tasks still receive the
     * scheduled retry if the bounded executor is full.
     */
    public void dispatchPendingTasks() {
        List<ForwardPauseTask> tasks = forwardPauseTaskMapper.selectList(new QueryWrapper<ForwardPauseTask>()
                .eq("status", 1).eq("task_status", "pending").last("LIMIT " + RETRY_BATCH_SIZE));
        for (ForwardPauseTask task : tasks) {
            dispatch(task.getId());
        }
    }

    private void enqueueForwards(QueryWrapper<Forward> query) {
        List<Forward> forwards = forwardService.list(query);
        for (Forward forward : forwards) {
            enqueue(forward.getId());
        }
    }

    private void dispatch(Long taskId) {
        if (taskId == null) return;
        // Claim before handing work to the bounded executor. Leaving a task
        // as pending while it waits in the JVM queue lets every 15-second
        // sweep enqueue duplicate copies of the same slow node operation.
        if (forwardPauseTaskMapper.claim(taskId, System.currentTimeMillis()) != 1) {
            return;
        }
        try {
            forwardPauseTaskExecutor.execute(taskId);
        } catch (TaskRejectedException exception) {
            // Return the durable row to pending; the bounded scheduler retries it.
            forwardPauseTaskMapper.markPending(taskId, "暂停任务执行器繁忙", System.currentTimeMillis());
            log.warn("暂停转发任务 {} 暂时排队失败，将在下一轮重试", taskId);
        } catch (RuntimeException exception) {
            forwardPauseTaskMapper.markPending(taskId, "暂停任务调度失败", System.currentTimeMillis());
            log.warn("暂停转发任务 {} 调度失败，将在下一轮重试", taskId, exception);
        }
    }

    private boolean isUserBlocked(User user) {
        return !Objects.equals(user.getStatus(), 1) || isExpired(user.getExpTime())
                || isQuotaExceeded(user.getFlow(), user.getInFlow(), user.getOutFlow());
    }

    private boolean isUserTunnelBlocked(UserTunnel userTunnel) {
        return !Objects.equals(userTunnel.getStatus(), 1) || isExpired(userTunnel.getExpTime())
                || isQuotaExceeded(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow());
    }

    private boolean isExpired(Long expTime) {
        return expTime != null && expTime <= System.currentTimeMillis();
    }

    private boolean isQuotaExceeded(Long quotaGb, Long inFlow, Long outFlow) {
        if (quotaGb == null || quotaGb <= 0) return true;
        try {
            return Math.addExact(inFlow == null ? 0 : inFlow, outFlow == null ? 0 : outFlow)
                    >= Math.multiplyExact(quotaGb, BYTES_TO_GB);
        } catch (ArithmeticException exception) {
            return true;
        }
    }
}

package com.admin.common.task;

import com.admin.common.constants.ForwardStatus;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.ForwardPauseTask;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.ForwardPauseTaskMapper;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Objects;

/**
 * Converts quota/expiry intents into the shared per-node synchronisation
 * workflow. The aggregate task remains a durable entitlement trigger; endpoint
 * acknowledgements now live in {@link ForwardSyncTaskService}.
 */
@Slf4j
@Service
public class ForwardPauseTaskExecutor {

    private static final long BYTES_TO_GB = 1024L * 1024 * 1024L;

    @Resource
    private ForwardPauseTaskMapper forwardPauseTaskMapper;

    @Resource
    private ForwardService forwardService;

    @Resource
    private UserService userService;

    @Resource
    private UserTunnelService userTunnelService;

    @Resource
    private ForwardSyncTaskService forwardSyncTaskService;

    @Async("flowPauseExecutor")
    public void execute(Long taskId) {
        if (taskId == null) return;
        ForwardPauseTask task = forwardPauseTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getTaskStatus(), "running")) return;

        try {
            Forward forward = forwardService.getById(task.getForwardId());
            if (forward == null || !requiresPause(forward)) {
                forwardPauseTaskMapper.deleteById(taskId);
                return;
            }

            R queued = forwardSyncTaskService.queueSafetyPause(forward.getId());
            if (queued.getCode() != 0) {
                forwardPauseTaskMapper.markPending(taskId, queued.getMsg(), System.currentTimeMillis());
                return;
            }
            // Endpoint tasks now own retries and the final status write.
            forwardPauseTaskMapper.deleteById(taskId);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            log.warn("暂停转发任务 {} 失败: {}", taskId, message);
            forwardPauseTaskMapper.markPending(taskId, message, System.currentTimeMillis());
        }
    }

    private boolean requiresPause(Forward forward) {
        if (Objects.equals(forward.getStatus(), ForwardStatus.PAUSED)
                || Objects.equals(forward.getStatus(), ForwardStatus.DELETING)) {
            return false;
        }
        User user = userService.getById(forward.getUserId());
        if (user == null) return true;
        if (Objects.equals(user.getRoleId(), 0)) return false;
        if (!Objects.equals(user.getStatus(), 1) || isExpired(user.getExpTime())
                || isQuotaExceeded(user.getFlow(), user.getInFlow(), user.getOutFlow())) {
            return true;
        }

        UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()), false);
        return userTunnel == null || !Objects.equals(userTunnel.getStatus(), 1)
                || isExpired(userTunnel.getExpTime())
                || isQuotaExceeded(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow());
    }

    private boolean isQuotaExceeded(Long quotaGb, Long inFlow, Long outFlow) {
        if (quotaGb == null || quotaGb <= 0) return true;
        try {
            long limit = Math.multiplyExact(quotaGb, BYTES_TO_GB);
            long used = Math.addExact(inFlow == null ? 0 : inFlow, outFlow == null ? 0 : outFlow);
            return used >= limit;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private boolean isExpired(Long expTime) {
        return expTime != null && expTime <= System.currentTimeMillis();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return exception.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}

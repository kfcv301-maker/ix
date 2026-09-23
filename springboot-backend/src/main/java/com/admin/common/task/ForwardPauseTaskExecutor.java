package com.admin.common.task;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.Set;

/** Performs slow node pause commands after the accounting transaction commits. */
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
    private TunnelService tunnelService;

    @Resource
    private TunnelIngressNodeResolver ingressNodeResolver;

    @Async("flowPauseExecutor")
    public void execute(Long taskId) {
        if (taskId == null) {
            return;
        }

        ForwardPauseTask task = forwardPauseTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getTaskStatus(), "running")) {
            return;
        }
        try {
            Forward forward = forwardService.getById(task.getForwardId());
            if (forward == null || !requiresPause(forward)) {
                forwardPauseTaskMapper.deleteById(taskId);
                return;
            }

            String failure = pauseEveryEndpoint(forward);
            if (failure != null) {
                forwardPauseTaskMapper.markPending(taskId, failure, System.currentTimeMillis());
                return;
            }

            UpdateWrapper<Forward> update = new UpdateWrapper<>();
            update.eq("id", forward.getId()).eq("status", 1)
                    .set("status", 0).set("updated_time", System.currentTimeMillis());
            if (!forwardService.update(null, update)) {
                Forward current = forwardService.getById(forward.getId());
                if (current != null && Objects.equals(current.getStatus(), 1)) {
                    forwardPauseTaskMapper.markPending(taskId, "节点已暂停，但面板状态写回失败", System.currentTimeMillis());
                    return;
                }
            }
            forwardPauseTaskMapper.deleteById(taskId);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            log.warn("暂停转发任务 {} 失败: {}", taskId, message);
            forwardPauseTaskMapper.markPending(taskId, message, System.currentTimeMillis());
        }
    }

    private boolean requiresPause(Forward forward) {
        if (!Objects.equals(forward.getStatus(), 1)) {
            return false;
        }
        User user = userService.getById(forward.getUserId());
        if (user == null) {
            return true;
        }
        if (Objects.equals(user.getRoleId(), 0)) {
            return false;
        }
        if (!Objects.equals(user.getStatus(), 1) || isExpired(user.getExpTime()) || isQuotaExceeded(
                user.getFlow(), user.getInFlow(), user.getOutFlow())) {
            return true;
        }

        UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()), false);
        return userTunnel == null || !Objects.equals(userTunnel.getStatus(), 1)
                || isExpired(userTunnel.getExpTime())
                || isQuotaExceeded(userTunnel.getFlow(), userTunnel.getInFlow(), userTunnel.getOutFlow());
    }

    private String pauseEveryEndpoint(Forward forward) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel == null) {
            return "隧道不存在";
        }
        UserTunnel userTunnel = userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", forward.getUserId()).eq("tunnel_id", forward.getTunnelId()), false);
        int userTunnelId = userTunnel == null ? 0 : userTunnel.getId();
        String serviceName = forward.getId() + "_" + forward.getUserId() + "_" + userTunnelId;

        Set<Long> ingressNodeIds = ingressNodeResolver.resolveNodeIds(tunnel);
        if (ingressNodeIds.isEmpty()) {
            return "隧道没有可暂停的入口节点";
        }
        for (Long nodeId : ingressNodeIds) {
            GostDto result = GostUtil.PauseService(nodeId, serviceName);
            if (!isGostSuccess(result)) {
                return "入口节点 " + nodeId + " 暂停失败：" + gostMessage(result);
            }
        }
        if (Objects.equals(tunnel.getType(), 2)) {
            GostDto result = GostUtil.PauseRemoteService(tunnel.getOutNodeId(), serviceName);
            if (!isGostSuccess(result)) {
                return "出口节点 " + tunnel.getOutNodeId() + " 暂停失败：" + gostMessage(result);
            }
        }
        return null;
    }

    private boolean isQuotaExceeded(Long quotaGb, Long inFlow, Long outFlow) {
        if (quotaGb == null || quotaGb <= 0) {
            return true;
        }
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

    private boolean isGostSuccess(GostDto result) {
        return result != null && Objects.equals(result.getMsg(), "OK");
    }

    private String gostMessage(GostDto result) {
        return result == null || result.getMsg() == null ? "无响应" : result.getMsg();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}

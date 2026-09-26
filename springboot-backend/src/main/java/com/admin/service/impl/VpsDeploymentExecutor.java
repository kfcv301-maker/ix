package com.admin.service.impl;

import com.admin.entity.VpsDeploymentTask;
import com.admin.entity.VpsHost;
import com.admin.entity.User;
import com.admin.mapper.VpsDeploymentTaskMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.VpsHostService;
import com.admin.service.VpsSshService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/** Runs a reviewed remote template outside the HTTP request thread. */
@Service
public class VpsDeploymentExecutor {

    private static final int ACTIVE_STATUS = 1;
    private static final int MAX_LOG_LENGTH = 64 * 1024;

    @Resource
    private VpsDeploymentTaskMapper taskMapper;

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private VpsSshService vpsSshService;

    @Async("vpsDeploymentTaskExecutor")
    public void execute(Long taskId) {
        VpsDeploymentTask task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() == null || task.getStatus() != ACTIVE_STATUS) return;
        VpsHost host = vpsHostService.getById(task.getVpsId());
        if (host == null || host.getStatus() == null || host.getStatus() != ACTIVE_STATUS) {
            finish(task, "failed", "VPS 已被移除，任务未执行。\n");
            return;
        }
        User requester = userMapper.selectById(task.getRequestedByUserId());
        boolean administrator = requester != null && Integer.valueOf(0).equals(requester.getRoleId());
        if (requester == null || !Integer.valueOf(ACTIVE_STATUS).equals(requester.getStatus())
                || !vpsHostService.canOperate(task.getRequestedByUserId(), administrator, host.getId())) {
            finish(task, "cancelled", "发起人的 VPS 权限已被撤销，任务未执行。\n");
            return;
        }
        if (!vpsHostService.isFingerprintVerified(host)) {
            finish(task, "cancelled", "SSH 主机指纹尚未确认，任务未执行。\n");
            return;
        }

        // Claim the task atomically. A queued task may have been cancelled by
        // the timeout reaper while this asynchronous worker was waiting.
        if (!setRunning(task)) return;
        try {
            String password = vpsHostService.decryptSshPassword(host);
            if (password == null || password.trim().isEmpty()) {
                finish(task, "failed", "SSH 凭据无法解密，任务已取消。\n");
                return;
            }
            append(task.getId(), "开始执行受限部署模板：" + task.getTaskType() + "\n");
            VpsSshService.CommandResult result = vpsSshService.runTemplate(host, password, task.getTaskType(),
                    chunk -> append(task.getId(), chunk));
            vpsHostService.recordSshSuccess(host, result.getFingerprint(), "受限部署任务已建立 SSH 连接");
            finish(task, result.isSucceeded() ? "succeeded" : "failed", "\n" + result.getMessage() + "\n");
        } catch (Exception exception) {
            vpsHostService.recordSshFailure(host, safeMessage(exception),
                    exception instanceof VpsSshService.HostFingerprintChangedException);
            finish(task, "failed", "\n任务异常：" + safeMessage(exception) + "\n");
        }
    }

    private boolean setRunning(VpsDeploymentTask task) {
        return taskMapper.update(null, new UpdateWrapper<VpsDeploymentTask>()
                .eq("id", task.getId())
                .eq("status", ACTIVE_STATUS)
                .eq("task_status", "pending")
                .set("task_status", "running")
                .set("started_time", System.currentTimeMillis())
                .set("updated_time", System.currentTimeMillis())) == 1;
    }

    private synchronized void append(Long taskId, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        VpsDeploymentTask current = taskMapper.selectById(taskId);
        if (current == null) return;
        String oldLog = current.getOutputLog() == null ? "" : current.getOutputLog();
        String nextLog = oldLog + redact(chunk);
        if (nextLog.length() > MAX_LOG_LENGTH) {
            nextLog = "[较早日志已截断]\n" + nextLog.substring(nextLog.length() - MAX_LOG_LENGTH + 16);
        }
        VpsDeploymentTask update = new VpsDeploymentTask();
        update.setId(taskId);
        update.setOutputLog(nextLog);
        update.setUpdatedTime(System.currentTimeMillis());
        taskMapper.updateById(update);
    }

    private void finish(VpsDeploymentTask task, String status, String finalMessage) {
        append(task.getId(), finalMessage);
        taskMapper.update(null, new UpdateWrapper<VpsDeploymentTask>().eq("id", task.getId())
                .set("task_status", status)
                .set("finished_time", System.currentTimeMillis())
                .set("updated_time", System.currentTimeMillis())
                .set("active_lock", null));
    }

    /** A process crash must not leave a VPS permanently locked. */
    @Scheduled(fixedDelay = 300_000L)
    public void expireStalledTasks() {
        long deadline = System.currentTimeMillis() - 40L * 60 * 1000;
        for (VpsDeploymentTask task : taskMapper.selectList(new QueryWrapper<VpsDeploymentTask>()
                .eq("status", ACTIVE_STATUS).in("task_status", "pending", "running")
                .lt("created_time", deadline))) {
            finish(task, "failed", "\n任务超时未完成，已自动释放 VPS 锁。\n");
        }
    }

    private String redact(String value) {
        return value.replaceAll("(?i)(password|token|secret)\\s*[:=]\\s*\\S+", "$1=***");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return "远程 SSH 执行失败";
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}

package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.TunnelForwardDiagnosisLimiter;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.service.ForwardService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Executes one-click tunnel diagnostics away from the HTTP request. Each job
 * has a small probe concurrency, a fixed wall-clock limit, cancellation and a
 * short retention period; navigating away cannot leave an unbounded scan
 * consuming Agent commands indefinitely.
 */
@Service
public class TunnelForwardDiagnosisTaskService {

    private static final int PROBES_PER_TASK = 2;
    private static final long TASK_TIMEOUT_MILLIS = 90_000L;
    private static final long TASK_RETENTION_MILLIS = 10 * 60_000L;

    private final Map<String, DiagnosisTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();

    @Resource
    private ForwardService forwardService;

    @Resource
    private TunnelForwardDiagnosisLimiter limiter;

    @Resource
    @Qualifier("tunnelDiagnosisCoordinatorExecutor")
    private TaskExecutor coordinatorExecutor;

    @Resource
    @Qualifier("tunnelDiagnosisProbeExecutor")
    private TaskExecutor probeExecutor;

    public R start(Tunnel tunnel, Integer requesterUserId, List<Forward> forwards) {
        if (tunnel == null || requesterUserId == null) return R.err("无法确定诊断请求归属");
        final TunnelForwardDiagnosisLimiter.Lease lease;
        try {
            lease = limiter.acquire(requesterUserId, tunnel.getId());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return R.err(429, exception.getMessage());
        }

        DiagnosisTask task = new DiagnosisTask(UUID.randomUUID().toString(), tunnel, requesterUserId,
                forwards == null ? Collections.emptyList() : forwards);
        tasks.put(task.id, task);
        try {
            coordinatorExecutor.execute(() -> execute(task, lease));
        } catch (TaskRejectedException | IllegalStateException exception) {
            tasks.remove(task.id);
            lease.close();
            return R.err(429, "诊断任务队列繁忙，请稍后重试");
        }
        return R.ok(task.snapshot());
    }

    public R get(String taskId, Integer requesterUserId, boolean administrator) {
        DiagnosisTask task = tasks.get(taskId);
        if (task == null) return R.err("诊断任务不存在或已过期");
        if (!administrator && !task.requesterUserId.equals(requesterUserId)) {
            return R.err(403, "没有查看此诊断任务的权限");
        }
        return R.ok(task.snapshot());
    }

    public R cancel(String taskId, Integer requesterUserId, boolean administrator) {
        DiagnosisTask task = tasks.get(taskId);
        if (task == null) return R.err("诊断任务不存在或已过期");
        if (!administrator && !task.requesterUserId.equals(requesterUserId)) {
            return R.err(403, "没有取消此诊断任务的权限");
        }
        if (task.isTerminal()) return R.ok(task.snapshot());
        task.cancel();
        return R.ok(task.snapshot());
    }

    @Scheduled(fixedDelay = 60_000L)
    public void clearExpiredTasks() {
        long now = System.currentTimeMillis();
        for (DiagnosisTask task : tasks.values()) {
            if (!task.isTerminal() && now - task.createdAt > TASK_TIMEOUT_MILLIS + 30_000L) {
                task.cancel();
            }
            if (task.isTerminal() && now - task.updatedAt > TASK_RETENTION_MILLIS) {
                tasks.remove(task.id, task);
            }
        }
    }

    private void execute(DiagnosisTask task, TunnelForwardDiagnosisLimiter.Lease lease) {
        task.markRunning();
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT_MILLIS;
        List<CompletableFuture<ProbeResult>> active = new CopyOnWriteArrayList<>();
        ArrayDeque<Forward> pending = new ArrayDeque<>(task.forwards);
        boolean timedOut = false;
        try {
            while ((!pending.isEmpty() || !active.isEmpty()) && !task.cancelled) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                    break;
                }
                while (!pending.isEmpty() && active.size() < PROBES_PER_TASK && !task.cancelled) {
                    Forward forward = pending.removeFirst();
                    try {
                        active.add(CompletableFuture.supplyAsync(() -> diagnose(forward), probeExecutor));
                    } catch (TaskRejectedException | IllegalStateException exception) {
                        task.addReport(failedReport(forward, "诊断探测队列繁忙"));
                    }
                }

                boolean progressed = false;
                for (CompletableFuture<ProbeResult> future : new ArrayList<>(active)) {
                    if (!future.isDone()) continue;
                    active.remove(future);
                    progressed = true;
                    try {
                        task.addReport(future.join().report);
                    } catch (Exception exception) {
                        // The failed probe already contains a useful synthetic
                        // result when possible; this branch protects the job.
                        task.addFailure("探测任务异常");
                    }
                }
                if (!progressed && (!pending.isEmpty() || !active.isEmpty())) {
                    Thread.sleep(100L);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            task.cancel();
        } catch (Exception exception) {
            task.addFailure("诊断任务异常：" + safeMessage(exception));
        } finally {
            for (CompletableFuture<ProbeResult> future : active) future.cancel(true);
            String terminalReason = task.cancelled ? "用户已取消检测" : timedOut ? "检测任务总时限已到" : null;
            if (terminalReason != null) {
                task.addMissingReports(terminalReason);
            }
            task.finish(task.cancelled ? "cancelled" : timedOut ? "timed_out"
                    : task.hasError() ? "failed" : "completed");
            lease.close();
        }
    }

    private ProbeResult diagnose(Forward forward) {
        try {
            return new ProbeResult(reportForForward(forward, forwardService.diagnoseForwardForSystem(forward.getId())));
        } catch (Exception exception) {
            return new ProbeResult(failedReport(forward, "检测异常：" + safeMessage(exception)));
        }
    }

    private Map<String, Object> reportForForward(Forward forward, R diagnosis) {
        JSONObject report = diagnosis == null || diagnosis.getData() == null
                ? new JSONObject()
                : JSONObject.parseObject(JSON.toJSONString(diagnosis.getData()));
        report.put("forwardId", forward.getId());
        report.put("forwardName", forward.getName());
        report.put("remoteAddress", forward.getRemoteAddr());
        boolean success = diagnosis != null && diagnosis.getCode() == 0
                && areAllPingChecksSuccessful(report.getJSONArray("results"));
        report.put("success", success);
        if (!success && StringUtils.isBlank(report.getString("message"))) {
            report.put("message", diagnosis != null && diagnosis.getCode() == 0
                    ? "至少一个链路检测未通过" : diagnosis == null ? "未收到诊断结果" : diagnosis.getMsg());
        }
        return new LinkedHashMap<>(report);
    }

    private Map<String, Object> failedReport(Forward forward, String message) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("forwardId", forward.getId());
        report.put("forwardName", forward.getName());
        report.put("remoteAddress", forward.getRemoteAddr());
        report.put("success", false);
        report.put("message", message);
        report.put("results", Collections.emptyList());
        return report;
    }

    private boolean areAllPingChecksSuccessful(JSONArray results) {
        if (results == null || results.isEmpty()) return false;
        for (int index = 0; index < results.size(); index++) {
            JSONObject result = results.getJSONObject(index);
            if (result == null || !result.getBooleanValue("success")) return false;
        }
        return true;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (StringUtils.isBlank(message)) return exception.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static final class ProbeResult {
        private final Map<String, Object> report;

        private ProbeResult(Map<String, Object> report) {
            this.report = report;
        }
    }

    private static final class DiagnosisTask {
        private final String id;
        private final Long tunnelId;
        private final String tunnelName;
        private final Integer requesterUserId;
        private final List<Forward> forwards;
        private final Map<Long, Map<String, Object>> reports = new LinkedHashMap<>();
        private final long createdAt = System.currentTimeMillis();
        private volatile long updatedAt = createdAt;
        private volatile String status = "queued";
        private volatile boolean cancelled;
        private String taskError;

        private DiagnosisTask(String id, Tunnel tunnel, Integer requesterUserId, List<Forward> forwards) {
            this.id = id;
            this.tunnelId = tunnel.getId();
            this.tunnelName = tunnel.getName();
            this.requesterUserId = requesterUserId;
            this.forwards = new ArrayList<>(forwards);
        }

        private synchronized void markRunning() {
            if (!cancelled) {
                status = "running";
                updatedAt = System.currentTimeMillis();
            }
        }

        private synchronized void addReport(Map<String, Object> report) {
            Object value = report.get("forwardId");
            if (!(value instanceof Number)) return;
            reports.putIfAbsent(((Number) value).longValue(), report);
            updatedAt = System.currentTimeMillis();
        }

        private synchronized void addFailure(String message) {
            taskError = message;
            updatedAt = System.currentTimeMillis();
        }

        private synchronized void addMissingReports(String message) {
            for (Forward forward : forwards) {
                if (!reports.containsKey(forward.getId())) {
                    Map<String, Object> report = new LinkedHashMap<>();
                    report.put("forwardId", forward.getId());
                    report.put("forwardName", forward.getName());
                    report.put("remoteAddress", forward.getRemoteAddr());
                    report.put("success", false);
                    report.put("message", message);
                    report.put("results", Collections.emptyList());
                    reports.put(forward.getId(), report);
                }
            }
            updatedAt = System.currentTimeMillis();
        }

        private synchronized void cancel() {
            cancelled = true;
            if (!isTerminal()) status = "cancelling";
            updatedAt = System.currentTimeMillis();
        }

        private synchronized void finish(String nextStatus) {
            if (!forwards.isEmpty() && reports.size() < forwards.size()) {
                addMissingReports(taskError == null ? "诊断任务未完成" : taskError);
            }
            status = nextStatus;
            updatedAt = System.currentTimeMillis();
        }

        private synchronized boolean hasError() {
            return taskError != null;
        }

        private boolean isTerminal() {
            return "completed".equals(status) || "cancelled".equals(status)
                    || "timed_out".equals(status) || "failed".equals(status);
        }

        private synchronized Map<String, Object> snapshot() {
            List<Map<String, Object>> ordered = new ArrayList<>();
            int successful = 0;
            for (Forward forward : forwards) {
                Map<String, Object> report = reports.get(forward.getId());
                if (report == null) continue;
                ordered.add(new LinkedHashMap<>(report));
                if (Boolean.TRUE.equals(report.get("success"))) successful++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", id);
            data.put("status", status);
            data.put("tunnelId", tunnelId);
            data.put("tunnelName", tunnelName);
            data.put("createdAt", createdAt);
            data.put("updatedAt", updatedAt);
            data.put("totalForwards", forwards.size());
            data.put("completedForwards", reports.size());
            data.put("successfulForwards", successful);
            data.put("failedForwards", Math.max(0, reports.size() - successful));
            data.put("forwards", ordered);
            if (taskError != null) data.put("message", taskError);
            return data;
        }
    }
}

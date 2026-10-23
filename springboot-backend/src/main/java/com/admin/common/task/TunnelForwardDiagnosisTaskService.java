package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.TunnelForwardDiagnosisLimiter;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.ForwardService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;

/** One owned job automatically advances through bounded, sequential batches. */
@Service
public class TunnelForwardDiagnosisTaskService {
    static final int BATCH_SIZE = 20;
    private static final int PROBES_PER_TASK = 2;
    private static final long TASK_RETENTION_MILLIS = 10 * 60_000L;
    private final LongSupplier clock;
    private final long batchTimeoutMillis;
    private final long maxTaskTimeoutMillis;
    private final Map<String, DiagnosisTask> tasks = new java.util.concurrent.ConcurrentHashMap<>();

    @Resource private ForwardService forwardService;
    @Resource private UserMapper userMapper;
    @Resource private TunnelMapper tunnelMapper;
    @Resource private UserTunnelMapper userTunnelMapper;
    @Resource private TunnelForwardDiagnosisLimiter limiter;
    @Resource @Qualifier("tunnelDiagnosisCoordinatorExecutor") private TaskExecutor coordinatorExecutor;
    @Resource @Qualifier("tunnelDiagnosisProbeExecutor") private TaskExecutor probeExecutor;

    public TunnelForwardDiagnosisTaskService() { this(System::currentTimeMillis, 90_000L, 15 * 60_000L); }

    // A clock and short limits let timeout tests avoid real node waits.
    TunnelForwardDiagnosisTaskService(LongSupplier clock, long batchTimeoutMillis, long maxTaskTimeoutMillis) {
        this.clock = clock;
        this.batchTimeoutMillis = batchTimeoutMillis;
        this.maxTaskTimeoutMillis = maxTaskTimeoutMillis;
    }

    public R start(Tunnel tunnel, Integer requesterUserId, List<Forward> forwards) {
        if (tunnel == null || tunnel.getId() == null || requesterUserId == null) return R.err("无法确定诊断请求归属");
        DiagnosisTask task = new DiagnosisTask(UUID.randomUUID().toString(), tunnel, requesterUserId, forwards);
        final TunnelForwardDiagnosisLimiter.Lease lease;
        try { lease = limiter.acquire(requesterUserId, tunnel.getId()); }
        catch (IllegalArgumentException | IllegalStateException exception) { return R.err(429, exception.getMessage()); }
        tasks.put(task.id, task);
        if (task.forwards.isEmpty()) {
            task.finish("completed", null);
            lease.close();
            return R.ok(task.snapshot());
        }
        try { coordinatorExecutor.execute(() -> execute(task, lease)); }
        catch (TaskRejectedException | IllegalStateException exception) {
            tasks.remove(task.id); lease.close();
            return R.err(429, "诊断任务队列繁忙，请稍后重试");
        }
        return R.ok(task.snapshot());
    }

    public R get(String taskId, Integer requesterUserId, boolean administrator) {
        DiagnosisTask task = taskId == null ? null : tasks.get(taskId);
        if (task == null) return R.err(404, "诊断任务不存在或已过期");
        if (!administrator && !task.requesterUserId.equals(requesterUserId)) return R.err(403, "没有查看此诊断任务的权限");
        return R.ok(task.snapshot());
    }

    public R cancel(String taskId, Integer requesterUserId, boolean administrator) {
        DiagnosisTask task = taskId == null ? null : tasks.get(taskId);
        if (task == null) return R.err(404, "诊断任务不存在或已过期");
        if (!administrator && !task.requesterUserId.equals(requesterUserId)) return R.err(403, "没有取消此诊断任务的权限");
        task.finish("cancelled", "用户已取消剩余检测");
        return R.ok(task.snapshot());
    }

    @Scheduled(fixedDelay = 60_000L)
    public void clearExpiredTasks() {
        long now = clock.getAsLong();
        for (DiagnosisTask task : tasks.values()) {
            if (!task.isTerminal() && now >= task.deadlineAt) task.finish("timed_out", "检测任务总时限已到，剩余转发未检测");
            if (task.isTerminal() && now - task.updatedAt > TASK_RETENTION_MILLIS) tasks.remove(task.id, task);
        }
    }

    private void execute(DiagnosisTask task, TunnelForwardDiagnosisLimiter.Lease lease) {
        Map<FutureTask<Map<String, Object>>, Forward> active = new LinkedHashMap<>();
        try {
            if (!task.markRunning()) return;
            for (int offset = 0; offset < task.forwards.size() && !task.isTerminal(); offset += BATCH_SIZE) {
                int end = Math.min(offset + BATCH_SIZE, task.forwards.size());
                int next = offset;
                long batchDeadline = Math.min(task.deadlineAt, clock.getAsLong() + batchTimeoutMillis);
                task.beginBatch(offset / BATCH_SIZE + 1, batchDeadline);
                while ((next < end || !active.isEmpty()) && !task.isTerminal()) {
                    if (clock.getAsLong() >= batchDeadline) {
                        // Never launch a later batch while timed-out node probes
                        // may still be finishing. The global probe pool is bounded too.
                        task.finish("timed_out", clock.getAsLong() >= task.deadlineAt
                                ? "检测任务总时限已到，剩余转发未检测"
                                : "当前批次检测超时，已停止后续批次；剩余转发未检测");
                        break;
                    }
                    while (next < end && active.size() < PROBES_PER_TASK && !task.isTerminal()) {
                        Forward forward = task.forwards.get(next++);
                        FutureTask<Map<String, Object>> future = new FutureTask<>(() -> diagnose(task, forward));
                        try { probeExecutor.execute(future); active.put(future, forward); }
                        catch (TaskRejectedException | IllegalStateException exception) {
                            task.addReport(failedReport(forward, "诊断探测队列繁忙", "failed"));
                        }
                    }
                    boolean progressed = false;
                    for (var entry : new ArrayList<>(active.entrySet())) {
                        if (!entry.getKey().isDone()) continue;
                        active.remove(entry.getKey()); progressed = true;
                        try { task.addReport(entry.getKey().get()); }
                        catch (Exception exception) { task.addReport(failedReport(entry.getValue(), "探测任务异常", "failed")); }
                    }
                    if (!progressed && (next < end || !active.isEmpty()) && !task.isTerminal()) Thread.sleep(100L);
                }
                if (!task.isTerminal()) task.completeBatch();
            }
            task.finish("completed", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); task.finish("cancelled", "检测已中止，剩余转发未检测");
        } catch (Exception exception) { task.finish("failed", "诊断任务异常：" + safeMessage(exception)); }
        finally {
            // FutureTask (unlike CompletableFuture.cancel) interrupts a running
            // worker, so a pending Agent-response wait can stop promptly.
            for (var future : active.keySet()) future.cancel(true);
            lease.close();
        }
    }

    /** Background workers have no HTTP JWT context: re-read the actual requester. */
    private User authorizedRequester(DiagnosisTask task) {
        User requester = userMapper.selectById(task.requesterUserId);
        Tunnel tunnel = tunnelMapper.selectById(task.tunnelId);
        String error = null;
        if (requester == null || !Objects.equals(requester.getStatus(), 1)) error = "检测账号已停用或不存在";
        else if (tunnel == null) error = "隧道已删除";
        else if (!Objects.equals(requester.getRoleId(), 0)) {
            UserTunnel grant = userTunnelMapper.selectOne(new QueryWrapper<UserTunnel>()
                    .eq("user_id", task.requesterUserId).eq("tunnel_id", task.tunnelId));
            long now = clock.getAsLong();
            if (requester.getExpTime() != null && requester.getExpTime() <= now) error = "检测账号已到期";
            else if (!Objects.equals(tunnel.getStatus(), 1)) error = "隧道已停用，检测权限已撤销";
            else if (tunnel.getOwnerUserId() != null && !Objects.equals(tunnel.getOwnerUserId(), task.requesterUserId.longValue())) error = "隧道归属已变化，检测权限已撤销";
            else if (grant == null || !Objects.equals(grant.getStatus(), 1)
                    || (grant.getExpTime() != null && grant.getExpTime() <= now)) error = "隧道检测权限已撤销或到期";
        }
        if (error != null) { task.finish("failed", error + "，已停止剩余检测"); return null; }
        return requester;
    }

    private Map<String, Object> diagnose(DiagnosisTask task, Forward forward) {
        if (!withinDeadline(task) || Thread.currentThread().isInterrupted()) return failedReport(forward, "检测已中止", "not_run");
        try {
            User requester = authorizedRequester(task);
            if (requester == null) return failedReport(forward, "检测权限已撤销", "not_run");
            Forward latest = forwardService.getById(forward.getId());
            if (latest == null || !Objects.equals(latest.getUserId(), forward.getUserId())
                    || !Objects.equals(latest.getTunnelId(), forward.getTunnelId())
                    || !Objects.equals(latest.getRemoteAddr(), forward.getRemoteAddr())
                    || (!Objects.equals(requester.getRoleId(), 0) && !Objects.equals(latest.getUserId(), task.requesterUserId))) {
                return failedReport(forward, "转发已删除或配置、归属已变化，请重新检测", "failed");
            }
            if (task.isTerminal() || Thread.currentThread().isInterrupted()) return failedReport(forward, "检测已中止", "cancelled");
            return reportForForward(forward, forwardService.diagnoseForwardForSystem(forward,
                    () -> withinDeadline(task) && authorizedRequester(task) != null));
        } catch (Exception exception) { return failedReport(forward, "检测异常：" + safeMessage(exception), "failed"); }
    }

    private boolean withinDeadline(DiagnosisTask task) {
        if (task.isTerminal()) return false;
        long now = clock.getAsLong();
        if (now >= task.deadlineAt || now >= task.batchDeadlineAt) {
            task.finish("timed_out", "检测时限已到，已停止后续批次；剩余转发未检测");
            return false;
        }
        return true;
    }

    private Map<String, Object> reportForForward(Forward forward, R diagnosis) {
        JSONObject report = diagnosis == null || diagnosis.getData() == null ? new JSONObject()
                : JSONObject.parseObject(JSON.toJSONString(diagnosis.getData()));
        report.put("forwardId", forward.getId()); report.put("forwardName", forward.getName());
        report.put("remoteAddress", forward.getRemoteAddr());
        boolean success = diagnosis != null && diagnosis.getCode() == 0 && areAllPingChecksSuccessful(report.getJSONArray("results"));
        report.put("success", success); report.put("outcome", success ? "passed" : "failed");
        if (!success && StringUtils.isBlank(report.getString("message"))) report.put("message", diagnosis != null && diagnosis.getCode() == 0
                ? "至少一个链路检测未通过" : diagnosis == null ? "未收到诊断结果" : diagnosis.getMsg());
        if (report.get("results") == null) report.put("results", Collections.emptyList());
        return new LinkedHashMap<>(report);
    }

    private Map<String, Object> failedReport(Forward forward, String message, String outcome) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("forwardId", forward.getId()); report.put("forwardName", forward.getName());
        report.put("remoteAddress", forward.getRemoteAddr()); report.put("success", false);
        report.put("outcome", outcome); report.put("message", message); report.put("results", Collections.emptyList());
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

    private final class DiagnosisTask {
        private final String id;
        private final Long tunnelId;
        private final String tunnelName;
        private final Integer requesterUserId;
        private final List<Forward> forwards;
        private final Map<Long, Map<String, Object>> reports = new LinkedHashMap<>();
        private final long createdAt = clock.getAsLong();
        private final long deadlineAt;
        private volatile long updatedAt = createdAt;
        private volatile String status = "queued";
        private int currentBatch;
        private int completedBatches;
        private long batchDeadlineAt;
        private String taskMessage;

        private DiagnosisTask(String id, Tunnel tunnel, Integer requesterUserId, List<Forward> sources) {
            this.id = id; this.tunnelId = tunnel.getId(); this.tunnelName = tunnel.getName(); this.requesterUserId = requesterUserId;
            // Freeze identity/order/display data and deduplicate IDs rather than retaining mutable ORM objects.
            Map<Long, Forward> selected = new LinkedHashMap<>();
            if (sources != null) for (Forward source : sources) {
                if (source == null || source.getId() == null) continue;
                Forward copy = new Forward();
                copy.setId(source.getId()); copy.setUserId(source.getUserId()); copy.setTunnelId(source.getTunnelId());
                copy.setName(source.getName()); copy.setRemoteAddr(source.getRemoteAddr()); selected.putIfAbsent(copy.getId(), copy);
            }
            this.forwards = List.copyOf(selected.values());
            this.deadlineAt = createdAt + Math.min(maxTaskTimeoutMillis, Math.max(1L, totalBatches()) * batchTimeoutMillis);
        }

        private int totalBatches() { return (forwards.size() + BATCH_SIZE - 1) / BATCH_SIZE; }
        private synchronized boolean markRunning() {
            if (isTerminal()) return false;
            if (clock.getAsLong() >= deadlineAt) { finish("timed_out", "检测任务等待超时，尚未执行检测"); return false; }
            status = "running"; updatedAt = clock.getAsLong(); return true;
        }
        private synchronized void beginBatch(int batch, long deadline) {
            if (isTerminal()) return;
            currentBatch = batch; batchDeadlineAt = deadline; updatedAt = clock.getAsLong();
        }
        private synchronized void completeBatch() { if (!isTerminal()) { completedBatches++; updatedAt = clock.getAsLong(); } }
        private synchronized void addReport(Map<String, Object> report) {
            if (isTerminal()) return;
            Object value = report.get("forwardId");
            if (value instanceof Number) reports.putIfAbsent(((Number) value).longValue(), report);
            updatedAt = clock.getAsLong();
        }
        private synchronized void finish(String nextStatus, String message) {
            if (isTerminal()) return;
            if (!"completed".equals(nextStatus)) {
                String outcome = "cancelled".equals(nextStatus) ? "cancelled" : "timed_out".equals(nextStatus) ? "timed_out" : "not_run";
                for (Forward forward : forwards) reports.computeIfAbsent(forward.getId(), ignored -> failedReport(forward, message, outcome));
            }
            status = nextStatus; taskMessage = message; updatedAt = clock.getAsLong();
        }
        private boolean isTerminal() {
            return "completed".equals(status) || "cancelled".equals(status) || "timed_out".equals(status) || "failed".equals(status);
        }
        private synchronized Map<String, Object> snapshot() {
            List<Map<String, Object>> ordered = new ArrayList<>();
            int successful = 0, failed = 0, skipped = 0;
            for (Forward forward : forwards) {
                Map<String, Object> report = reports.get(forward.getId());
                if (report == null) continue;
                // Nested probe arrays are copied too; snapshots cannot mutate stored results.
                ordered.add(JSONObject.parseObject(JSON.toJSONString(report)));
                if (Boolean.TRUE.equals(report.get("success"))) successful++;
                else if ("failed".equals(report.get("outcome"))) failed++;
                else skipped++;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", id); data.put("status", status); data.put("tunnelId", tunnelId); data.put("tunnelName", tunnelName);
            data.put("createdAt", createdAt); data.put("updatedAt", updatedAt); data.put("deadlineAt", deadlineAt);
            data.put("batchSize", BATCH_SIZE); data.put("totalBatches", totalBatches()); data.put("currentBatch", currentBatch);
            data.put("completedBatches", completedBatches); data.put("batchDeadlineAt", batchDeadlineAt);
            data.put("totalForwards", forwards.size()); data.put("completedForwards", successful + failed);
            data.put("successfulForwards", successful); data.put("failedForwards", failed); data.put("skippedForwards", skipped);
            data.put("forwards", ordered);
            if (taskMessage != null) data.put("message", taskMessage);
            return data;
        }
    }
}

package com.admin.common.service;

import com.admin.common.dto.FlowAccountingResult;
import com.admin.common.dto.FlowAccountingContext;
import com.admin.common.dto.FlowDto;
import com.admin.entity.FlowReportCursor;
import com.admin.entity.Node;
import com.admin.mapper.FlowAccountingMapper;
import com.admin.mapper.FlowReportCursorMapper;
import com.admin.mapper.ForwardPauseTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates and records one agent report in a short database transaction.
 * Node commands are deliberately not sent from here: a slow/offline node must
 * never turn a successfully committed report into an HTTP retry.
 */
@Service
@Slf4j
public class FlowAccountingService {

    private static final Pattern SERVICE_NAME = Pattern.compile("^(\\d+)_(\\d+)_(\\d+)$");
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,96}$");
    private static final long MAX_REPORT_BYTES = 4L * 1024 * 1024 * 1024 * 1024;
    private static final long MAX_FUTURE_SESSION_MILLIS = 5 * 60 * 1000L;
    private static final long CURSOR_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000;
    private static final long BYTES_TO_GB = 1024L * 1024 * 1024L;

    @Resource
    private FlowAccountingMapper flowAccountingMapper;

    @Resource
    private FlowReportCursorMapper flowReportCursorMapper;

    @Resource
    private ForwardPauseTaskMapper forwardPauseTaskMapper;

    @Transactional
    public FlowAccountingResult account(Node reportingNode, FlowDto report) {
        if (reportingNode == null || reportingNode.getId() == null || report == null) {
            return FlowAccountingResult.rejected("节点或报告为空");
        }
        if (!hasCurrentReportProtocol(report)) {
            return FlowAccountingResult.upgradeRequired();
        }
        if (!hasValidTraffic(report)) {
            return FlowAccountingResult.rejected("流量数据非法");
        }

        ParsedServiceName parsedService = parseServiceName(report.getN());
        if (parsedService == null) {
            return FlowAccountingResult.rejected("服务名格式非法");
        }

        // One indexed join resolves the forward, owner, requested assignment,
        // tunnel and multi-ingress relationship. Do not trust IDs embedded in
        // the agent-provided service name beyond matching this database row.
        FlowAccountingContext context = flowAccountingMapper.selectContext(parsedService.forwardId,
                parsedService.userTunnelId, reportingNode.getId());
        if (context == null || !Objects.equals(context.getForwardId(), parsedService.forwardId)
                || !Objects.equals(asLong(context.getForwardUserId()), parsedService.userId)) {
            return FlowAccountingResult.rejected("转发与服务名不匹配");
        }
        if (!Objects.equals(context.getForwardStatus(), 1)) {
            return FlowAccountingResult.rejected("转发未处于运行状态");
        }
        if (!Objects.equals(context.getTunnelStatus(), 1) || !Objects.equals(context.getIngressNode(), 1)) {
            return FlowAccountingResult.rejected("节点不属于该转发的入口");
        }
        if (!Objects.equals(context.getOwnerId(), asLong(context.getForwardUserId()))) {
            return FlowAccountingResult.rejected("转发归属用户不存在");
        }
        boolean hasValidUserTunnel = isValidUserTunnel(context, parsedService.userTunnelId);
        if (!hasValidUserTunnel && !isAdministratorForward(context, parsedService.userTunnelId)) {
            return FlowAccountingResult.rejected("服务名中的用户隧道权限不匹配");
        }

        long now = System.currentTimeMillis();
        flowReportCursorMapper.insertIfAbsent(reportingNode.getId(), report.getN(), report.getReportSessionId(),
                report.getReportSessionStartedAt(), now);
        FlowReportCursor cursor = flowReportCursorMapper.selectForUpdate(reportingNode.getId(), report.getN());
        CursorDecision cursorDecision = evaluateCursor(cursor, report);
        if (cursorDecision == CursorDecision.DUPLICATE) {
            return FlowAccountingResult.duplicate();
        }
        if (cursorDecision == CursorDecision.REJECTED) {
            return FlowAccountingResult.rejected("报告会话或序号过期");
        }

        TrafficDelta delta;
        try {
            delta = scaleTraffic(report, context);
        } catch (IllegalArgumentException exception) {
            return FlowAccountingResult.rejected(exception.getMessage());
        }

        // A fractional traffic ratio may legitimately truncate a tiny report
        // to zero under the historical billing rule. Still acknowledge its
        // sequence, but do not mistake MySQL's "0 changed rows" for a failed
        // update and make the agent retry forever.
        if (delta.download > 0 || delta.upload > 0) {
            if (flowAccountingMapper.incrementForwardAndUser(context.getForwardId(), context.getOwnerId(),
                    delta.download, delta.upload) <= 0
                    || (hasValidUserTunnel && flowAccountingMapper.incrementUserTunnel(context.getUserTunnelId(),
                    context.getOwnerId(), context.getTunnelId().intValue(), delta.download, delta.upload) != 1)) {
                throw new IllegalStateException("流量计费记录不完整");
            }
        }

        // Persist pause intent before the transaction completes. The controller
        // only dispatches it after commit, so a lost HTTP response cannot lose
        // the compensation work or make the agent wait on a node command.
        int queuedPauseTasks = 0;
        if (!Objects.equals(context.getOwnerRoleId(), 0)) {
            queuedPauseTasks += forwardPauseTaskMapper.enqueueBlockedUserForwards(context.getOwnerId(), now, BYTES_TO_GB);
            if (hasValidUserTunnel) {
                queuedPauseTasks += forwardPauseTaskMapper.enqueueBlockedUserTunnelForwards(context.getOwnerId(), context.getUserTunnelId(), now,
                        BYTES_TO_GB);
            }
        }

        if (flowReportCursorMapper.updateCursor(reportingNode.getId(), report.getN(), report.getReportSessionId(),
                report.getReportSessionStartedAt(), report.getReportSequence(), now) != 1) {
            throw new IllegalStateException("更新流量报告游标失败");
        }
        return FlowAccountingResult.accepted(context.getOwnerId(),
                hasValidUserTunnel ? context.getUserTunnelId().longValue() : null, queuedPauseTasks > 0);
    }

    private boolean hasCurrentReportProtocol(FlowDto report) {
        if (report.getReportSessionId() == null || !SESSION_ID.matcher(report.getReportSessionId()).matches()
                || report.getReportSequence() == null || report.getReportSequence() <= 0
                || report.getReportSessionStartedAt() == null || report.getReportSessionStartedAt() <= 0) {
            return false;
        }
        return report.getReportSessionStartedAt() <= System.currentTimeMillis() + MAX_FUTURE_SESSION_MILLIS;
    }

    /** Retention is bounded by services, not reports; remove deleted services eventually. */
    @Scheduled(cron = "0 20 3 * * ?")
    public void purgeInactiveCursors() {
        int removed = flowReportCursorMapper.deleteOlderThan(System.currentTimeMillis() - CURSOR_RETENTION_MILLIS);
        if (removed > 0) {
            log.info("已清理 {} 条过期流量报告游标", removed);
        }
    }

    private boolean hasValidTraffic(FlowDto report) {
        return report.getU() != null && report.getD() != null
                && report.getU() >= 0 && report.getD() >= 0
                && report.getU() <= MAX_REPORT_BYTES && report.getD() <= MAX_REPORT_BYTES;
    }

    private ParsedServiceName parseServiceName(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        Matcher matcher = SERVICE_NAME.matcher(serviceName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new ParsedServiceName(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isValidUserTunnel(FlowAccountingContext context, Long expectedUserTunnelId) {
        return expectedUserTunnelId != null && expectedUserTunnelId > 0
                && expectedUserTunnelId <= Integer.MAX_VALUE
                && Objects.equals(context.getUserTunnelId(), expectedUserTunnelId.intValue())
                && Objects.equals(context.getUserTunnelUserId(), context.getForwardUserId())
                && Objects.equals(context.getUserTunnelTunnelId(), context.getForwardTunnelId());
    }

    private boolean isAdministratorForward(FlowAccountingContext context, Long userTunnelId) {
        return Objects.equals(context.getOwnerRoleId(), 0) && Objects.equals(userTunnelId, 0L);
    }

    private CursorDecision evaluateCursor(FlowReportCursor cursor, FlowDto report) {
        if (cursor == null || cursor.getSessionStartedAt() == null || cursor.getLastSequence() == null) {
            throw new IllegalStateException("流量报告游标不存在");
        }
        int sessionAgeComparison = report.getReportSessionStartedAt().compareTo(cursor.getSessionStartedAt());
        if (sessionAgeComparison < 0) {
            return CursorDecision.DUPLICATE;
        }
        if (sessionAgeComparison == 0 && !Objects.equals(report.getReportSessionId(), cursor.getSessionId())) {
            return CursorDecision.REJECTED;
        }
        if (sessionAgeComparison == 0 && report.getReportSequence() <= cursor.getLastSequence()) {
            return CursorDecision.DUPLICATE;
        }
        return CursorDecision.ACCEPT;
    }

    private TrafficDelta scaleTraffic(FlowDto report, FlowAccountingContext context) {
        int flowType = context.getTunnelFlow() == null ? 0 : context.getTunnelFlow();
        if (flowType <= 0 || flowType > 2) {
            throw new IllegalArgumentException("隧道流量计费类型非法");
        }
        BigDecimal ratio = context.getTrafficRatio() == null ? BigDecimal.ONE : context.getTrafficRatio();
        return new TrafficDelta(scale(report.getD(), ratio, flowType), scale(report.getU(), ratio, flowType));
    }

    private long scale(long bytes, BigDecimal ratio, int flowType) {
        // Keep the historical billing rule: truncate the traffic ratio first,
        // then apply the one-/two-way multiplier. Multiplying before the
        // truncation changes charges for fractional ratios (for example,
        // 1 byte * 1.5 * 2 used to be billed as 2, not 3).
        BigDecimal ratioAdjusted = BigDecimal.valueOf(bytes).multiply(ratio);
        if (ratioAdjusted.signum() < 0 || ratioAdjusted.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("计费流量超出允许范围");
        }
        try {
            return Math.multiplyExact(ratioAdjusted.longValue(), flowType);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("计费流量超出允许范围");
        }
    }

    private Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private enum CursorDecision {
        ACCEPT,
        DUPLICATE,
        REJECTED
    }

    private static final class ParsedServiceName {
        private final Long forwardId;
        private final Long userId;
        private final Long userTunnelId;

        private ParsedServiceName(Long forwardId, Long userId, Long userTunnelId) {
            this.forwardId = forwardId;
            this.userId = userId;
            this.userTunnelId = userTunnelId;
        }
    }

    private static final class TrafficDelta {
        private final long download;
        private final long upload;

        private TrafficDelta(long download, long upload) {
            this.download = download;
            this.upload = upload;
        }
    }
}

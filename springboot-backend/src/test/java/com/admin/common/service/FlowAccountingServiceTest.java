package com.admin.common.service;

import com.admin.common.dto.FlowAccountingContext;
import com.admin.common.dto.FlowAccountingResult;
import com.admin.common.dto.FlowDto;
import com.admin.entity.FlowReportCursor;
import com.admin.entity.Node;
import com.admin.mapper.FlowAccountingMapper;
import com.admin.mapper.FlowReportCursorMapper;
import com.admin.mapper.ForwardPauseTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowAccountingServiceTest {

    @Test
    void rejectsServiceNameThatDoesNotMatchTheForwardOwner() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_777_9", 1);
        when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(validContext());

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertFalse(result.isAccepted());
        verify(fixtures.cursorMapper, never()).insertIfAbsent(anyLong(), anyString(), anyString(), anyLong(), anyLong());
        verify(fixtures.accountingMapper, never()).incrementForwardAndUser(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void duplicateSequenceDoesNotIncrementAnyCounter() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_3_9", 7);
        FlowReportCursor cursor = cursor(report, 7L);
        when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(validContext());
        when(fixtures.cursorMapper.selectForUpdate(5L, "12_3_9")).thenReturn(cursor);

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertTrue(result.isDuplicate());
        verify(fixtures.accountingMapper, never()).incrementForwardAndUser(anyLong(), anyLong(), anyLong(), anyLong());
        verify(fixtures.accountingMapper, never()).incrementUserTunnel(anyInt(), anyLong(), anyInt(), anyLong(), anyLong());
    }

    @Test
    void rejectsAReportFromANodeThatIsNotAnIngress() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_3_9", 1);
        FlowAccountingContext context = validContext();
        context.setIngressNode(0);
        when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(context);

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertFalse(result.isAccepted());
        verify(fixtures.cursorMapper, never()).insertIfAbsent(anyLong(), anyString(), anyString(), anyLong(), anyLong());
        verify(fixtures.accountingMapper, never()).incrementForwardAndUser(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsNegativeTrafficBeforeItTouchesTheDatabase() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_3_9", 1);
        report.setD(-1L);

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertFalse(result.isAccepted());
        verify(fixtures.accountingMapper, never()).selectContext(anyLong(), anyLong(), anyLong());
    }

    @Test
    void truncatesFractionalRatioPerDirection() {
        Fixtures fixtures = fixtures();

        Long billed = ReflectionTestUtils.invokeMethod(fixtures.service, "scale", 1L,
                new BigDecimal("1.5"));

        assertEquals(1L, billed);
    }

    @Test
    void validNodeCanOnlyBillItsOwnForwardAndCommitsOneSequence() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_3_9", 8);
        FlowReportCursor cursor = cursor(report, 7L);
        when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(validContext());
        when(fixtures.cursorMapper.selectForUpdate(5L, "12_3_9")).thenReturn(cursor);
        when(fixtures.accountingMapper.incrementForwardAndUser(12L, 3L, 1024L, 512L)).thenReturn(2);
        when(fixtures.accountingMapper.incrementUserTunnel(9, 3L, 42, 1024L, 512L)).thenReturn(1);
        when(fixtures.cursorMapper.updateCursor(eq(5L), eq("12_3_9"), eq(report.getReportSessionId()),
                eq(report.getReportSessionStartedAt()), eq(8L), anyLong())).thenReturn(1);

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertTrue(result.isAccepted());
        verify(fixtures.accountingMapper).incrementForwardAndUser(12L, 3L, 1024L, 512L);
        verify(fixtures.accountingMapper).incrementUserTunnel(9, 3L, 42, 1024L, 512L);
        verify(fixtures.cursorMapper).updateCursor(eq(5L), eq("12_3_9"), eq(report.getReportSessionId()),
                eq(report.getReportSessionStartedAt()), eq(8L), anyLong());
    }

    @Test
    void accountsAgentTcpAndUdpServiceNames() {
        for (String protocol : new String[]{"tcp", "udp"}) {
            Fixtures fixtures = fixtures();
            FlowDto report = report("12_3_9_" + protocol, 8);
            FlowReportCursor cursor = cursor(report, 7L);
            when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(validContext());
            when(fixtures.cursorMapper.selectForUpdate(5L, report.getN())).thenReturn(cursor);
            when(fixtures.accountingMapper.incrementForwardAndUser(12L, 3L, 1024L, 512L)).thenReturn(2);
            when(fixtures.accountingMapper.incrementUserTunnel(9, 3L, 42, 1024L, 512L)).thenReturn(1);
            when(fixtures.cursorMapper.updateCursor(eq(5L), eq(report.getN()), eq(report.getReportSessionId()),
                    eq(report.getReportSessionStartedAt()), eq(8L), anyLong())).thenReturn(1);

            assertTrue(fixtures.service.account(node(), report).isAccepted());
            verify(fixtures.accountingMapper).incrementForwardAndUser(12L, 3L, 1024L, 512L);
        }
    }

    @Test
    void acceptsOneSequencedTailReportImmediatelyAfterPause() {
        Fixtures fixtures = fixtures();
        FlowDto report = report("12_3_9", 8);
        FlowReportCursor cursor = cursor(report, 7L);
        FlowAccountingContext context = validContext();
        context.setForwardStatus(0);
        context.setFlowGraceUntil(System.currentTimeMillis() + 5_000L);
        when(fixtures.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(context);
        when(fixtures.cursorMapper.selectForUpdate(5L, "12_3_9")).thenReturn(cursor);
        when(fixtures.accountingMapper.incrementForwardAndUser(12L, 3L, 1024L, 512L)).thenReturn(2);
        when(fixtures.accountingMapper.incrementUserTunnel(9, 3L, 42, 1024L, 512L)).thenReturn(1);
        when(fixtures.cursorMapper.updateCursor(eq(5L), eq("12_3_9"), eq(report.getReportSessionId()),
                eq(report.getReportSessionStartedAt()), eq(8L), anyLong())).thenReturn(1);

        FlowAccountingResult result = fixtures.service.account(node(), report);

        assertTrue(result.isAccepted());
    }

    @Test
    void singleWayBillsOnlyUploadWhileTwoWayBillsEachDirectionOnce() {
        for (int mode : new int[]{1, 2}) {
            Fixtures f = fixtures();
            FlowDto report = report("12_3_9_tcp", 8);
            FlowAccountingContext context = validContext();
            context.setTunnelFlow(mode);
            long download = mode == 1 ? 0L : 1024L;
            when(f.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(context);
            when(f.cursorMapper.selectForUpdate(5L, report.getN())).thenReturn(cursor(report, 7L));
            when(f.accountingMapper.incrementForwardAndUser(12L, 3L, download, 512L)).thenReturn(2);
            when(f.accountingMapper.incrementUserTunnel(9, 3L, 42, download, 512L)).thenReturn(1);
            when(f.cursorMapper.updateCursor(eq(5L), eq(report.getN()), anyString(), anyLong(), eq(8L), anyLong())).thenReturn(1);
            assertTrue(f.service.account(node(), report).isAccepted());
            verify(f.accountingMapper).incrementForwardAndUser(12L, 3L, download, 512L);
        }
    }

    @Test
    void anArchivedAgentGrantBillsTheCanonicalGrantAndCannotChangeItsOwner() {
        Fixtures f = fixtures();
        FlowDto report = report("12_3_9_tcp", 8);
        FlowAccountingContext context = validContext();
        context.setUserTunnelId(13);
        context.setReportedUserTunnelId(9);
        when(f.accountingMapper.selectContext(12L, 9L, 5L)).thenReturn(context);
        when(f.cursorMapper.selectForUpdate(5L, report.getN())).thenReturn(cursor(report, 7L));
        when(f.accountingMapper.incrementForwardAndUser(12L, 3L, 1024L, 512L)).thenReturn(2);
        when(f.accountingMapper.incrementUserTunnel(13, 3L, 42, 1024L, 512L)).thenReturn(1);
        when(f.cursorMapper.updateCursor(eq(5L), eq(report.getN()), anyString(), anyLong(), eq(8L), anyLong())).thenReturn(1);
        assertTrue(f.service.account(node(), report).isAccepted());
        verify(f.accountingMapper).incrementUserTunnel(13, 3L, 42, 1024L, 512L);
        context.setUserTunnelUserId(777);
        assertFalse(f.service.account(node(), report).isAccepted());
    }

    private Fixtures fixtures() {
        FlowAccountingService service = new FlowAccountingService();
        FlowAccountingMapper accountingMapper = mock(FlowAccountingMapper.class);
        FlowReportCursorMapper cursorMapper = mock(FlowReportCursorMapper.class);
        ForwardPauseTaskMapper pauseTaskMapper = mock(ForwardPauseTaskMapper.class);
        ReflectionTestUtils.setField(service, "flowAccountingMapper", accountingMapper);
        ReflectionTestUtils.setField(service, "flowReportCursorMapper", cursorMapper);
        ReflectionTestUtils.setField(service, "forwardPauseTaskMapper", pauseTaskMapper);
        return new Fixtures(service, accountingMapper, cursorMapper, pauseTaskMapper);
    }

    private Node node() {
        Node node = new Node();
        node.setId(5L);
        return node;
    }

    private FlowDto report(String serviceName, long sequence) {
        FlowDto report = new FlowDto();
        report.setN(serviceName);
        report.setD(1024L);
        report.setU(512L);
        report.setReportSessionId("reporter_test_session");
        report.setReportSessionStartedAt(System.currentTimeMillis() - 1_000L);
        report.setReportSequence(sequence);
        return report;
    }

    private FlowReportCursor cursor(FlowDto report, long sequence) {
        FlowReportCursor cursor = new FlowReportCursor();
        cursor.setSessionId(report.getReportSessionId());
        cursor.setSessionStartedAt(report.getReportSessionStartedAt());
        cursor.setLastSequence(sequence);
        return cursor;
    }

    private FlowAccountingContext validContext() {
        FlowAccountingContext context = new FlowAccountingContext();
        context.setForwardId(12L);
        context.setForwardUserId(3);
        context.setForwardTunnelId(42);
        context.setForwardStatus(1);
        context.setTunnelId(42L);
        context.setTunnelStatus(1);
        context.setTunnelFlow(2);
        context.setTrafficRatio(BigDecimal.ONE);
        context.setIngressNode(1);
        context.setOwnerId(3L);
        context.setOwnerRoleId(1);
        context.setOwnerStatus(1);
        context.setOwnerFlow(10L);
        context.setOwnerInFlow(0L);
        context.setOwnerOutFlow(0L);
        context.setUserTunnelId(9);
        context.setUserTunnelUserId(3);
        context.setUserTunnelTunnelId(42);
        context.setUserTunnelStatus(1);
        context.setUserTunnelFlow(10L);
        context.setUserTunnelInFlow(0L);
        context.setUserTunnelOutFlow(0L);
        return context;
    }

    private static final class Fixtures {
        private final FlowAccountingService service;
        private final FlowAccountingMapper accountingMapper;
        private final FlowReportCursorMapper cursorMapper;
        private final ForwardPauseTaskMapper pauseTaskMapper;

        private Fixtures(FlowAccountingService service, FlowAccountingMapper accountingMapper,
                         FlowReportCursorMapper cursorMapper, ForwardPauseTaskMapper pauseTaskMapper) {
            this.service = service;
            this.accountingMapper = accountingMapper;
            this.cursorMapper = cursorMapper;
            this.pauseTaskMapper = pauseTaskMapper;
        }
    }
}

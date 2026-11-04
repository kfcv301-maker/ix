package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeContinuityObservationTest {
    static final long T=1_800_000_000_000L;
    private NodeContinuityObservation.Sample sample(String session,int seconds,long uptime) {
        return new NodeContinuityObservation.Sample("panel-1",session,T+seconds*1000L,uptime,2,4_000_000_000L,30_000_000_000L);
    }
    private NodeContinuityObservation.Event accept(NodeContinuityObservation.State s,String session,int seconds,long uptime) {
        return NodeContinuityObservation.accept(s,sample(session,seconds,uptime),T+seconds*1000L);
    }
    private NodeContinuityObservation.State baseline() {
        var s=new NodeContinuityObservation.State(); assertNull(accept(s,"a",0,10000));return s;
    }
    @Test void baselineIsNotAMachineChangeAndContinuousUptimeSupportsIpOnly() {
        var s=baseline(); assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T).classification());
        assertNull(accept(s,"a",60,10060));
        assertEquals("IP_ONLY_LIKELY",NodeContinuityObservation.classifyIp(s,T,T+60000).classification());
    }
    @Test void reconnectWithContinuousUptimeIsNotReplacement() {
        var s=baseline();assertNull(accept(s,"b",60,10060));
        assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T+60000).classification());
        var event=accept(s,"b",120,10120);
        assertEquals("RECONNECTED",event.kind());assertEquals("CONTINUOUS",event.evidence().classification());
        assertEquals("IP_ONLY_LIKELY",NodeContinuityObservation.classifyIp(s,T,T+120000).classification());
        assertNull(accept(s,"b",180,10180));
    }
    @Test void newMachineOrRebootWithSameIpIsRecordedIndependently() {
        var s=baseline();assertNull(accept(s,"b",60,5));
        var event=accept(s,"b",120,65);
        assertEquals("MACHINE_SUSPECTED",event.kind());assertEquals("BOOT_CHANGED",event.evidence().reason());
        assertEquals("MACHINE_SUSPECTED",NodeContinuityObservation.classifyIp(s,T,T+120000).classification());
        assertNull(accept(s,"b",180,125));
        assertEquals("IP_ONLY_LIKELY",NodeContinuityObservation.classifyIp(s,T+120000,T+180000).classification());
    }
    @Test void panelRestartNeverCreatesReplacementAndDoesNotCarryOldSuspicion() {
        var s=baseline();accept(s,"b",60,5);accept(s,"b",120,65);
        var n=new NodeContinuityObservation.Sample("panel-2","c",T+180000,10,8,8_000_000_000L,60_000_000_000L);
        assertNull(NodeContinuityObservation.accept(s,n,T+180000));
        assertEquals("PANEL_RESTART",s.getReason());
        assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T+180000).classification());
    }
    @Test void duplicateAndDelayedSamplesCannotConfirmCandidate() {
        var s=baseline();accept(s,"b",60,5);
        assertNull(accept(s,"b",60,5));assertNull(accept(s,"a",0,10000));assertNull(accept(s,"b",70,15));
        assertEquals("MACHINE_SUSPECTED",accept(s,"b",120,65).kind());
    }
    @Test void flappingSessionsNeedTwoSamplesFromSameNewConnection() {
        var s=baseline();accept(s,"b",60,5);assertNull(accept(s,"c",120,5));
        assertNull(accept(s,"d",180,5));assertEquals("MACHINE_SUSPECTED",accept(s,"d",240,65).kind());
    }
    @Test void missingZeroStaleAndFutureReportsNeverImplyReplacement() {
        for (var n : new NodeContinuityObservation.Sample[]{null,sample("b",60,0),sample("b",0,1),sample("b",61,1)}) {
            var s=baseline();assertNull(NodeContinuityObservation.accept(s,n,T+60000));
            assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T+60000).classification());
        }
    }
    @Test void missingReportsBreakContinuityEvenWhenTheNextSessionMatches() {
        var s=baseline();NodeContinuityObservation.accept(s,null,T+60000);accept(s,"a",120,10120);
        assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T+120000).classification());
    }
    @Test void longGapOnlyRecordsUnknownAndResetsBaseline() {
        var s=baseline();var e=accept(s,"b",1200,10);
        assertEquals("OBSERVATION_GAP",e.kind());assertEquals("UNKNOWN",e.evidence().classification());
        assertNull(accept(s,"b",1260,70));
    }
    @Test void badSameSessionUptimeDoesNotBecomeReplacement() {
        var s=baseline();assertNull(accept(s,"a",60,1));assertEquals("INCONSISTENT_UPTIME",s.getReason());
        assertEquals("UNKNOWN",NodeContinuityObservation.classifyIp(s,T,T+60000).classification());
    }
    @Test void unstableNewSessionUptimeRemainsUnknown() {
        var s=baseline();accept(s,"b",60,5);var e=accept(s,"b",120,5000);
        assertEquals("UNKNOWN",e.evidence().classification());assertEquals("RECONNECTED",e.kind());
    }
    @Test void hardwareChangesRequireReconnectAndTwoConsistentSamples() {
        var s=baseline();
        var first=new NodeContinuityObservation.Sample("panel-1","b",T+60000,10060,8,4_000_000_000L,30_000_000_000L);
        var second=new NodeContinuityObservation.Sample("panel-1","b",T+120000,10120,8,4_000_000_000L,30_000_000_000L);
        assertNull(NodeContinuityObservation.accept(s,first,T+60000));
        assertEquals("CONFIG_CHANGED",NodeContinuityObservation.accept(s,second,T+120000).evidence().reason());
    }
    @Test void tinyMemoryDriftAndMissingLegacyConfigurationDoNotImplyReplacement() {
        for (int cores:new int[]{0,2}) {
            var s=baseline();
            var a=new NodeContinuityObservation.Sample("panel-1","b",T+60000,10060,cores,4_010_000_000L,0);
            var b=new NodeContinuityObservation.Sample("panel-1","b",T+120000,10120,cores,4_010_000_000L,0);
            NodeContinuityObservation.accept(s,a,T+60000);
            assertEquals("CONTINUOUS",NodeContinuityObservation.accept(s,b,T+120000).evidence().classification());
        }
    }
    @Test void persistenceRetainsCandidateAndLegacyPayloadCreatesEmptyState() {
        var ip=JSON.parseObject("{\"target\":\"example.com\"}",NodeIpObservation.State.class);
        assertNotNull(ip.getContinuity());var s=baseline();accept(s,"b",60,5);ip.setContinuity(s);
        var restored=JSON.parseObject(JSON.toJSONString(ip),NodeIpObservation.State.class);
        assertEquals("MACHINE_SUSPECTED",accept(restored.getContinuity(),"b",120,65).kind());
    }
    @Test void clockJumpOnExistingConnectionIsNotReplacement() {
        var s=baseline();assertNull(accept(s,"a",600,10060));assertEquals("unknown",s.getStatus());
    }
}

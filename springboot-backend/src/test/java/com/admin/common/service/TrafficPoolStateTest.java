package com.admin.common.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficPoolStateTest {
    long now=Instant.parse("2026-11-08T00:00:00Z").toEpochMilli();
    @Test void transientFailureDoesNotRemoveAndDuplicateChecksDoNotConfirm() {
        var h=new TrafficPoolState.Health();
        h.observe(true,now,3,2);assertFalse(h.eligible);
        h.observe(true,now+1,3,2);assertFalse(h.eligible);
        h.observe(true,now+20000,3,2);assertTrue(h.eligible);
        h.observe(false,now+40000,3,2);assertTrue(h.eligible);
        h.observe(false,now+60000,3,2);assertTrue(h.eligible);
        h.observe(false,now+80000,3,2);assertFalse(h.eligible);
        h.observe(true,now+100000,3,2);assertFalse(h.eligible);
        h.observe(true,now+120000,3,2);assertTrue(h.eligible);
    }
    @Test void cumulativeCountersSurvivePanelRestartAndMachineCounterResetWithoutLosingUsed() {
        var m=new TrafficPoolState.Member();m.usedBytes=700;
        m.account(new TrafficPoolState.TrafficSample(now,100,1000,2000),now,1,"both");assertEquals(700,m.usedBytes);
        m.account(new TrafficPoolState.TrafficSample(now+20000,120,1100,2500),now+20000,1,"both");assertEquals(1300,m.usedBytes);
        m=com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(m),TrafficPoolState.Member.class);
        m.account(new TrafficPoolState.TrafficSample(now+40000,140,1150,2800),now+40000,1,"out");assertEquals(1600,m.usedBytes);
        m.account(new TrafficPoolState.TrafficSample(now+60000,5,3,4),now+60000,1,"both");assertEquals(1600,m.usedBytes);
        m.account(new TrafficPoolState.TrafficSample(now+80000,25,13,24),now+80000,1,"both");assertEquals(1630,m.usedBytes);
    }
    @Test void billingBoundaryDoesNotChargePreviousCycleTrafficWhenFirstSampleWasMissing() {
        var m=new TrafficPoolState.Member();
        m.account(new TrafficPoolState.TrafficSample(now,100,100,100),now,1,"both");m.usedBytes=999;
        long next=Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
        m.account(null,next,1,"both");assertEquals(0,m.usedBytes);
        m.account(new TrafficPoolState.TrafficSample(next+20000,1000,100000,100000),next+20000,1,"both");assertEquals(0,m.usedBytes);
    }
    @Test void monthEndAndTimezoneAreExplicit() {
        assertEquals("2026-02-28",TrafficPoolState.cycle(Instant.parse("2026-02-28T00:00:00Z").toEpochMilli(),31));
        assertEquals("2026-12-01",TrafficPoolState.cycle(Instant.parse("2026-11-30T16:00:00Z").toEpochMilli(),1));
    }
}

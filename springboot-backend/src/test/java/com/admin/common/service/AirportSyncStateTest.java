package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirportSyncStateTest {
    final long now=1_800_000_000_000L;
    AirportSyncState state() { var s=new AirportSyncState();s.enabled=true;return s; }
    AirportSyncState.Sample sample(String ip,long time,String boot) { return new AirportSyncState.Sample(ip,time,boot,"digest"); }
    @Test void freshInstallRequiresTwoDifferentReportsAndTenSeconds() {
        var s=state();
        assertFalse(s.observe(sample("8.8.8.8",now,"boot"),now));
        assertFalse(s.observe(sample("8.8.8.8",now,"boot"),now+15000));
        assertFalse(s.observe(sample("8.8.8.8",now+5000,"boot"),now+5000));
        assertTrue(s.observe(sample("8.8.8.8",now+10000,"boot"),now+10000));
        assertEquals(now+10000,s.pendingAt);
    }
    @Test void outageStatePersistsAcrossProcessRestartAndKeepsIdempotentTimestamp() {
        var s=state();s.observe(sample("8.8.8.8",now,"boot"),now);s.observe(sample("8.8.8.8",now+10000,"boot"),now+10000);
        s.completed("retry_transport",now+10000);
        s=JSON.parseObject(JSON.toJSONString(s),AirportSyncState.class);
        assertFalse(s.observe(sample("8.8.8.8",now+12000,"boot"),now+12000));
        assertTrue(s.observe(sample("8.8.8.8",now+16000,"boot"),now+16000));
        assertEquals(now+10000,s.pendingAt);
        s.completed("synced",now+16000);assertEquals("8.8.8.8",s.lastIp);assertEquals("",s.pendingIp);
    }
    @Test void replacementAndLatestIpSupersedeQueuedOldAddress() {
        var s=state();s.observe(sample("8.8.8.8",now,"old"),now);s.observe(sample("8.8.8.8",now+10000,"old"),now+10000);
        assertFalse(s.observe(sample("1.1.1.1",now+11000,"new"),now+11000));assertEquals("",s.pendingIp);
        assertTrue(s.observe(sample("1.1.1.1",now+22000,"new"),now+22000));assertEquals("1.1.1.1",s.pendingIp);
    }
    @Test void offlinePrivateFutureAndDisabledObservationsCannotDeliver() {
        var s=state();
        assertFalse(s.observe(null,now));assertFalse(s.observe(sample("127.0.0.1",now,"b"),now));
        assertFalse(s.observe(sample("8.8.8.8",now+1,"b"),now));
        s.enabled=false;assertFalse(s.observe(sample("8.8.8.8",now,"b"),now));
    }
    @Test void manualConflictStopsAllRetriesUntilAdminResaves() {
        var s=state();s.completed("blocked_409",now);
        assertTrue(s.blocked);assertFalse(s.observe(sample("8.8.8.8",now+20000,"b"),now+20000));
    }
    @Test void expiredEventUsesFreshObservationNotForgedOldTimestamp() {
        var s=state();s.observe(sample("8.8.8.8",now,"b"),now);s.observe(sample("8.8.8.8",now+10000,"b"),now+10000);
        assertTrue(s.observe(sample("8.8.8.8",now+260000,"b"),now+260000));assertEquals(now+260000,s.pendingAt);
    }
}

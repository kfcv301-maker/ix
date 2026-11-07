package com.admin.common.service;

import com.admin.common.migration.AirportSyncMigration;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSON;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real transactions and persisted JSON, not a mocked queue. No live endpoint is contacted. */
class AirportSyncServiceTest {
    final String endpoint="https://airport.example/api/v1/node-address-sync/report", binding="11111111-1111-1111-1111-111111111111", token="a".repeat(64);
    final String boot="11111111-1111-1111-1111-111111111111";
    final long base=1_800_000_000_000L;
    JdbcTemplate jdbc;DataSourceTransactionManager manager;
    NodeMapper nodes=mock(NodeMapper.class);UserMapper users=mock(UserMapper.class);
    AirportSyncTransport transport=mock(AirportSyncTransport.class);
    AirportSyncService service;Node node;
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE node(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO node VALUES(1),(2)");
        new AirportSyncMigration(jdbc).migrate();new AirportSyncMigration(jdbc).migrate();
        node=new Node();node.setId(1L);node.setSecret("node-secret-case-sensitive");when(nodes.selectById(1L)).thenReturn(node);
        var second=new Node();second.setId(2L);second.setSecret("other-node");when(nodes.selectById(2L)).thenReturn(second);
        var admin=new User();admin.setId(10L);admin.setRoleId(0);admin.setStatus(1);when(users.selectById(10L)).thenReturn(admin);
        var ordinary=new User();ordinary.setId(20L);ordinary.setRoleId(1);ordinary.setStatus(1);when(users.selectById(20L)).thenReturn(ordinary);
        service=newService();at(0);when(transport.send(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn("synced");
    }
    AirportSyncService newService() { return new AirportSyncService(jdbc,nodes,users,transport,manager,"isolated-fixture-encryption-key",new AirportConnectionService(jdbc,users,transport,manager,"isolated-fixture-encryption-key")); }
    void at(long elapsed) { service.clock=Clock.fixed(Instant.ofEpochMilli(base+elapsed),ZoneOffset.UTC); }
    AirportSyncService.Settings settings(long id,boolean enabled,long rev,String key) {
        return new AirportSyncService.Settings(id,enabled,endpoint,binding,key,rev,"aws-imds");
    }
    void report(long elapsed,String ip) {
        at(elapsed);assertTrue(service.report(node,new AirportSyncService.Report(ip,"i-1234567890abcdef0",(base+elapsed)/1000,boot)));service.process(1L);
    }
    @AfterEach void close() { service.close();jdbc.execute("DROP ALL OBJECTS"); }
    @Test void defaultOffAndOrdinaryOwnerHasNoAccess() {
        assertEquals(false,service.get(10L,1L).get("enabled"));
        assertEquals(false,service.observationPolicy(1).get("enabled"));
        node.setOwnerUserId(20L);
        assertThrows(SecurityException.class,()->service.get(20L,1L));
        assertThrows(SecurityException.class,()->service.save(20L,settings(1,true,0,token)));
        assertThrows(SecurityException.class,()->service.remove(20L,1L,0L));verifyNoInteractions(transport);
    }
    @Test void firstInstallReportsThenDeliversWithinSixtySecondsWithoutAgentCommands() {
        service.save(10L,settings(1,true,0,token));
        report(0,"8.8.4.4");verifyNoInteractions(transport);
        report(10000,"8.8.4.4");
        verify(transport).send(endpoint,binding,token,"8.8.4.4",(base+10000)/1000);
        assertEquals("synced",service.get(10L,1L).get("status"));
        assertEquals("8.8.4.4",service.get(10L,1L).get("lastIp"));
    }
    @Test void durableOutboxSurvivesServiceRecreationAndRejectsOldEvents() {
        service.save(10L,settings(1,true,0,token));
        when(transport.send(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn("retry_transport","synced");
        report(0,"8.8.4.4");report(10000,"8.8.4.4");
        service.close();service=newService();report(16000,"8.8.4.4");
        verify(transport,times(2)).send(endpoint,binding,token,"8.8.4.4",(base+10000)/1000);
        assertThrows(IllegalArgumentException.class,()->service.report(node,new AirportSyncService.Report("8.8.8.8","i-1234567890abcdef0",base/1000,boot)));
    }
    @Test void disableAndUnbindStopReportsAndRetriesAndNeverLeakToken() {
        service.save(10L,settings(1,true,0,token));report(0,"8.8.4.4");
        String persisted=jdbc.queryForObject("SELECT payload FROM node_airport_sync WHERE node_id=1",String.class);
        assertFalse(persisted.contains(token));assertFalse(JSON.toJSONString(service.get(10L,1L)).contains("encryptedToken"));
        assertFalse(JSON.toJSONString(service.get(10L,1L)).contains(token));
        service.save(10L,settings(1,false,1,""));at(10000);
        assertFalse(service.report(node,new AirportSyncService.Report("8.8.4.4","i-1234567890abcdef0",(base+10000)/1000,boot)));
        service.process(1L);verifyNoInteractions(transport);
        service.remove(10L,1L,2L);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM node_airport_sync",Integer.class));
    }
    @Test void explicitBindingIsUniqueAndCannotOverwriteOtherNodeRow() {
        service.save(10L,settings(1,true,0,token));
        assertThrows(IllegalArgumentException.class,()->service.save(10L,settings(2,true,0,"b".repeat(64))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM node_airport_sync",Integer.class));
        report(0,"8.8.4.4");report(10000,"8.8.4.4");
        verify(transport).send(endpoint,binding,token,"8.8.4.4",(base+10000)/1000);
    }
    @Test void changedDestinationCannotSilentlyReuseSecretAndStaleEditorsCannotOverwrite() {
        service.save(10L,settings(1,true,0,token));
        assertThrows(IllegalArgumentException.class,()->service.save(10L,new AirportSyncService.Settings(1L,true,"https://other.example/api/v1/node-address-sync/report",binding,"",1L,"aws-imds")));
        assertThrows(IllegalArgumentException.class,()->service.save(10L,settings(1,false,0,"")));
        assertTrue(service.needsReporter(1));
    }
    @Test void conflictHaltsQueueAndStopsOptionalCollectionUntilAdminResaves() {
        when(transport.send(anyString(),anyString(),anyString(),anyString(),anyLong())).thenReturn("blocked_409");
        service.save(10L,settings(1,true,0,token));report(0,"8.8.4.4");report(10000,"8.8.4.4");
        at(20000);service.process(1L);assertEquals(false,service.observationPolicy(1).get("enabled"));
        assertEquals(true,service.observationPolicy(1).get("installEnabled"));
        assertTrue(service.needsReporter(1));
        verify(transport,times(1)).send(anyString(),anyString(),anyString(),anyString(),anyLong());
    }
    @Test void disableSerializesWithInFlightDeliveryAndReturnsOnlyAfterItStops() throws Exception {
        service.save(10L,settings(1,true,0,token));report(0,"8.8.4.4");at(10000);
        service.report(node,new AirportSyncService.Report("8.8.4.4","i-1234567890abcdef0",(base+10000)/1000,boot));
        var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
        when(transport.send(anyString(),anyString(),anyString(),anyString(),anyLong())).thenAnswer(call->{started.countDown();assertTrue(finish.await(2,TimeUnit.SECONDS));return "synced";});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var sending=pool.submit(()->service.process(1));assertTrue(started.await(2,TimeUnit.SECONDS));
            var disabling=pool.submit(()->service.save(10L,settings(1,false,1,"")));
            Thread.sleep(100);assertFalse(disabling.isDone());finish.countDown();sending.get(2,TimeUnit.SECONDS);disabling.get(2,TimeUnit.SECONDS);
            service.process(1);verify(transport,times(1)).send(anyString(),anyString(),anyString(),anyString(),anyLong());
        }
    }
    @Test void oldMachineCannotOverwriteAfterHandoverEvenAfterSettingsResaveAndRestart() {
        service.save(10L,settings(1,true,0,token));report(0,"8.8.4.4");report(10000,"8.8.4.4");
        at(20000);assertFalse(service.report(node,new AirportSyncService.Report("8.8.8.8","i-abcdef01234567890",(base+20000)/1000,boot)));
        service.process(1);assertEquals("source_conflict",service.get(10L,1L).get("status"));
        at(55000);assertTrue(service.report(node,new AirportSyncService.Report("8.8.8.8","i-abcdef01234567890",(base+55000)/1000,boot)));service.process(1);
        at(65000);assertTrue(service.report(node,new AirportSyncService.Report("8.8.8.8","i-abcdef01234567890",(base+65000)/1000,boot)));service.process(1);
        verify(transport).send(endpoint,binding,token,"8.8.8.8",(base+65000)/1000);
        service.save(10L,settings(1,true,1,""));service.close();service=newService();at(80000);
        assertFalse(service.report(node,new AirportSyncService.Report("8.8.4.4","i-1234567890abcdef0",(base+80000)/1000,boot)));service.process(1);
        verify(transport,times(2)).send(anyString(),anyString(),anyString(),anyString(),anyLong());
    }
    @Test void healthyFiveSecondPollingUpdatesWithinThirtySecondsAndReplacementWithinSixty() {
        service.save(10L,settings(1,true,0,token));report(0,"8.8.4.4");report(10000,"8.8.4.4");
        long changedAt=10001;
        for(long t=15000;t<=30000;t+=5000) report(t,"8.8.8.8");
        assertEquals("8.8.8.8",service.get(10L,1L).get("lastIp"));
        assertTrue((Long)service.get(10L,1L).get("lastSuccessAt")-base-changedAt<=30000);
        long replacedAt=30001;
        for(long t=35000;t<=70000;t+=5000) {
            at(t);service.report(node,new AirportSyncService.Report("1.1.1.1","i-abcdef01234567890",(base+t)/1000,boot));service.process(1);
        }
        assertEquals("1.1.1.1",service.get(10L,1L).get("lastIp"));
        assertTrue((Long)service.get(10L,1L).get("lastSuccessAt")-base-replacedAt<=60000);
    }
    @Test void v2ConnectionOnlineDisableAndCredentialEditGateAllDeliveries() {
        var connections=new AirportConnectionService(jdbc,users,transport,manager,"isolated-fixture-encryption-key");
        String remote="33333333-3333-3333-3333-333333333333";
        var c=connections.save(10L,new AirportConnectionService.Settings("","airport","https://airport.example",remote,token,true,0L));String id=(String)c.get("id");
        var check=com.alibaba.fastjson2.JSONObject.of("protocol_version",2,"status","authorized","server_time",1800000000,"connection_id",remote,"relay_panel_id",connections.panelId(),
                "mappings",List.of(com.alibaba.fastjson2.JSONObject.of("binding_id",binding,"relay_node_id",1,"label","node","enabled",true)));
        when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(new AirportSyncTransport.Reply("ok",com.alibaba.fastjson2.JSONObject.of("data",check).toJSONString()));
        connections.check(10L,id,1);
        service.save(10L,new AirportSyncService.Settings(1L,true,"",binding,"",0L,"aws-imds",id));
        var receipt=com.alibaba.fastjson2.JSONObject.of("protocol_version",2,"status","updated","updated",1,"connection_id",remote,"relay_panel_id",connections.panelId(),"relay_node_id",1,"binding_id",binding,"ip","8.8.4.4");
        when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(new AirportSyncTransport.Reply("ok",com.alibaba.fastjson2.JSONObject.of("data",receipt).toJSONString()));
        report(0,"8.8.4.4");report(10000,"8.8.4.4");assertEquals("synced",service.get(10L,1L).get("status"));
        clearInvocations(transport);
        connections.save(10L,new AirportConnectionService.Settings(id,"airport","https://airport.example",remote,"",false,2L));
        report(15000,"8.8.8.8");report(30000,"8.8.8.8");verifyNoInteractions(transport);
        assertEquals("blocked_connection",service.get(10L,1L).get("status"));
    }
    @Test void successfulRecheckAfterAirportAuthorizationRepairUnblocksWithoutNodeReinstallation() {
        var connections=new AirportConnectionService(jdbc,users,transport,manager,"isolated-fixture-encryption-key");
        String remote="33333333-3333-3333-3333-333333333333";
        String id=(String)connections.save(10L,new AirportConnectionService.Settings("","airport","https://airport.example",remote,token,true,0L)).get("id");
        var check=com.alibaba.fastjson2.JSONObject.of("protocol_version",2,"status","authorized","server_time",1800000000,"connection_id",remote,"relay_panel_id",connections.panelId(),
                "mappings",List.of(com.alibaba.fastjson2.JSONObject.of("binding_id",binding,"relay_node_id",1,"label","node","enabled",true)));
        var good=new AirportSyncTransport.Reply("ok",com.alibaba.fastjson2.JSONObject.of("data",check).toJSONString());
        when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(good);connections.check(10L,id,1);
        service.save(10L,new AirportSyncService.Settings(1L,true,"",binding,"",0L,"aws-imds",id));
        when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(new AirportSyncTransport.Reply("blocked_403",""));
        report(0,"8.8.4.4");report(10000,"8.8.4.4");assertEquals(false,service.observationPolicy(1).get("enabled"));
        when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(good);connections.check(10L,id,2);
        at(20000);service.process(1);assertEquals(true,service.observationPolicy(1).get("enabled"));
    }
}

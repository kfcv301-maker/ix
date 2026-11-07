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
    AirportSyncService newService() { return new AirportSyncService(jdbc,nodes,users,transport,manager,"isolated-fixture-encryption-key"); }
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
}

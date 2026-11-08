package com.admin.common.service;

import com.admin.common.migration.TrafficPoolMigration;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.mapper.*;
import com.admin.entity.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TrafficPoolServiceTest {
    JdbcTemplate jdbc;NodeMapper nodes=mock(NodeMapper.class);UserMapper users=mock(UserMapper.class);
    TunnelMapper tunnels=mock(TunnelMapper.class);ForwardMapper forwards=mock(ForwardMapper.class);
    ForwardPortReservationService ports=mock(ForwardPortReservationService.class);TunnelIngressNodeResolver ingress=mock(TunnelIngressNodeResolver.class);
    TrafficPoolObserver observer=mock(TrafficPoolObserver.class);NodeGroupService groups;TrafficPoolService service;
    long now=Instant.parse("2026-11-08T00:00:00Z").toEpochMilli();
    static class Dns extends TrafficPoolDns {
        final List<Record> records=new ArrayList<>();final List<String> actions=new ArrayList<>();
        @Override public List<Record> list(String zone,String domain,String token){return List.copyOf(records);}
        @Override public Record create(String z,String d,String ip,int ttl,String owner,String token){var r=new Record(String.format("%032x",records.size()+1),ip.contains(":")?"AAAA":"A",d,ip,owner,false);records.add(r);actions.add("add:"+ip);return r;}
        @Override public void delete(String z,Record r,String domain,String owner,String token){assertEquals(owner,r.comment());records.remove(r);actions.add("remove:"+r.content());}
        @Override public void adopt(String z,Record r,String domain,String owner,String token){records.remove(r);records.add(new Record(r.id(),r.type(),r.name(),r.content(),owner,false));actions.add("adopt:"+r.content());}
    }
    Dns dns=new Dns();
    @BeforeEach void setup(){
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE node(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO node VALUES(1),(2)");
        jdbc.execute("CREATE TABLE vps_host(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO vps_host VALUES(1)");
        jdbc.execute("CREATE TABLE tunnel(id BIGINT PRIMARY KEY)");jdbc.update("INSERT INTO tunnel VALUES(10)");
        jdbc.execute("CREATE TABLE tunnel_entry_node(id BIGINT AUTO_INCREMENT PRIMARY KEY,tunnel_id BIGINT,node_id BIGINT,created_time BIGINT,status INT)");
        new TrafficPoolMigration(jdbc).migrate();new TrafficPoolMigration(jdbc).migrate();
        var admin=new User();admin.setId(9L);admin.setRoleId(0);admin.setStatus(1);when(users.selectById(9L)).thenReturn(admin);
        var ordinary=new User();ordinary.setId(8L);ordinary.setRoleId(1);ordinary.setStatus(1);when(users.selectById(8L)).thenReturn(ordinary);
        for(long id=1;id<=2;id++){var n=new Node();n.setId(id);n.setName("node "+id);n.setServerIp(id==1?"8.8.8.8":"1.1.1.1");n.setPortSta(10000);n.setPortEnd(60000);when(nodes.selectById(id)).thenReturn(n);when(observer.addresses(n)).thenReturn(List.of(n.getServerIp()));}
        when(observer.probe(anyString(),anyInt())).thenReturn(true);
        when(observer.probeAll(anyCollection(),anyInt())).thenAnswer(call->{var result=new HashMap<String,Boolean>();for(String ip:(Collection<String>)call.getArgument(0))result.put(ip,observer.probe(ip,call.getArgument(1)));return result;});
        var tm=new DataSourceTransactionManager(ds);groups=new NodeGroupService(jdbc,users,nodes,tm);
        service=new TrafficPoolService(jdbc,groups,nodes,tunnels,forwards,ports,ingress,dns,observer,tm,"isolated-test-key");service.startedAt=now-120000;at(0);
    }
    void at(long elapsed){service.clock=Clock.fixed(Instant.ofEpochMilli(now+elapsed),ZoneOffset.UTC);when(observer.metrics(anyLong(),anyLong())).thenReturn(new TrafficPoolState.TrafficSample(now+elapsed,100+elapsed/1000,100+elapsed,200+elapsed));}
    TrafficPoolService.Settings settings(String id,long revision,boolean dnsOn,List<TrafficPoolService.MemberInput> members,List<Long> tunnelIds){return new TrafficPoolService.Settings(id,"fixture pool",revision,"individual","both",0,1,"pool.example.com","a".repeat(32),id.isEmpty()?"fixture-token-"+"x".repeat(32):"",dnsOn,dnsOn?"pool.example.com":"",10000,3,2,60,true,members,tunnelIds);}
    List<TrafficPoolService.MemberInput> inputs(){return List.of(new TrafficPoolService.MemberInput(1,true,0,null),new TrafficPoolService.MemberInput(2,true,0,null));}
    String create(boolean enabled){return (String)service.save(9L,settings("",0,enabled,inputs(),List.of())).get("id");}
    @AfterEach void close(){service.close();jdbc.execute("DROP ALL OBJECTS");}
    @Test void defaultOffAndAdministratorAuthorizationAreEnforcedOnServer(){
        assertThrows(SecurityException.class,()->service.list(8L));assertThrows(SecurityException.class,()->groups.list(8L));
        var id=create(false);service.process(id);at(20000);service.process(id);assertTrue(dns.actions.isEmpty());
        assertFalse(service.get(9L,id).containsKey("encryptedToken"));assertFalse(service.get(9L,id).toString().contains("fixture-token"));
        assertFalse(jdbc.queryForObject("SELECT payload FROM traffic_pool",String.class).contains("fixture-token"));
    }
    @Test void naturalHealthRecoveryAddsThenThreeFailuresRemoveOnlyOneManagedAddress(){
        String id=create(true);service.process(id);assertTrue(dns.actions.isEmpty());at(20000);service.process(id);
        at(40000);service.process(id);assertEquals(2,dns.records.size());assertEquals("synced",service.get(9L,id).get("dnsStatus"));
        when(observer.probe("8.8.8.8",10000)).thenReturn(false);
        at(60000);service.process(id);at(80000);service.process(id);assertEquals(2,dns.records.size());
        at(100000);service.process(id);assertEquals(List.of("1.1.1.1"),dns.records.stream().map(TrafficPoolDns.Record::content).toList());
        assertEquals("remove:8.8.8.8",dns.actions.getLast());
    }
    @Test void staleEditorsCannotOverwriteAndDnsDomainsCannotBelongToTwoPools(){
        var id=create(false);service.save(9L,settings(id,1,false,inputs(),List.of()));
        assertThrows(IllegalArgumentException.class,()->service.save(9L,settings(id,1,false,inputs(),List.of())));
        assertEquals(2L,service.get(9L,id).get("revision"));
    }
    @Test void groupDeleteUnassignsBothInventoriesWithoutDeletingAnything(){
        groups.save(9L,new NodeGroupService.Settings("","HK",0));var g=groups.list(9L).getFirst();String id=(String)g.get("id");
        groups.assign(9L,id,List.of(1L));groups.assign(9L,id,List.of(1L),true);
        assertThrows(SecurityException.class,()->groups.assign(8L,id,List.of(2L)));
        groups.remove(9L,id,1);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM node_group_member",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vps_group_member",Integer.class));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM node",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vps_host",Integer.class));verifyNoInteractions(ports);
    }
    @Test void portConflictRollsBackPoolMemberAndTunnelAdmission(){
        var tunnel=new Tunnel();tunnel.setId(10L);tunnel.setType(1);tunnel.setInNodeId(1L);when(tunnels.selectById(10L)).thenReturn(tunnel);when(ingress.resolveNodeIds(tunnel)).thenReturn(Set.of(1L));
        var f=new Forward();f.setId(20L);f.setInPort(10000);when(forwards.selectList(any())).thenReturn(List.of(f));
        var lease=mock(ForwardPortReservationService.ReservationLease.class);when(lease.isSuccess()).thenReturn(false);when(lease.getMessage()).thenReturn("conflicting port");when(ports.reserve(f,tunnel)).thenReturn(lease);
        assertThrows(IllegalArgumentException.class,()->service.save(9L,settings("",0,false,inputs(),List.of(10L))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tunnel_entry_node",Integer.class));
    }
    @Test void unverifiedInventoryKeepsNewMachineOutOfDns(){
        var tunnel=new Tunnel();tunnel.setId(10L);tunnel.setType(1);tunnel.setInNodeId(1L);when(tunnels.selectById(10L)).thenReturn(tunnel);when(ingress.resolveNodeIds(tunnel)).thenReturn(Set.of(1L));when(forwards.selectList(any())).thenReturn(List.of());
        var id=(String)service.save(9L,settings("",0,true,inputs(),List.of(10L))).get("id");
        service.process(id);at(20000);service.process(id);assertTrue(dns.records.isEmpty());
        assertTrue(((List<?>)service.get(9L,id).get("desiredAddresses")).isEmpty());
    }
    @Test void changingIpAddsConfirmedNewAddressBeforeDeletingOldAddress(){
        var id=create(true);service.process(id);at(20000);service.process(id);
        when(observer.addresses(nodes.selectById(1L))).thenReturn(List.of("8.8.4.4"));
        at(40000);service.process(id);at(60000);service.process(id);assertTrue(dns.actions.contains("add:8.8.4.4"));assertFalse(dns.actions.contains("remove:8.8.8.8"));
        at(80000);service.process(id);assertTrue(dns.actions.indexOf("add:8.8.4.4")<dns.actions.indexOf("remove:8.8.8.8"));
    }
    @Test void missingControlHeartbeatAndUnknownProbeDoNotWithdrawHealthyDataPlane(){
        var id=create(true);service.process(id);at(20000);service.process(id);
        at(40000);when(observer.metrics(anyLong(),anyLong())).thenReturn(null);service.process(id);assertEquals(2,dns.records.size());
        when(observer.probeAll(anyCollection(),anyInt())).thenReturn(Map.of());
        for(long t=60000;t<=140000;t+=20000){at(t);service.process(id);}assertEquals(2,dns.records.size());assertTrue(dns.actions.stream().noneMatch(a->a.startsWith("remove:")));
    }
    @Test void simultaneousFailuresHoldExistingRecordsByDefault(){
        var id=create(true);service.process(id);at(20000);service.process(id);when(observer.probe(anyString(),anyInt())).thenReturn(false);
        for(long t=40000;t<=100000;t+=20000){at(t);service.process(id);}
        assertEquals(2,dns.records.size());assertEquals("all_unhealthy_held",service.get(9L,id).get("dnsStatus"));
    }
    @Test void adoptionRequiresUnchangedPreviewAndCurrentMemberAddresses(){
        var id=create(false);dns.records.add(new TrafficPoolDns.Record("f".repeat(32),"A","pool.example.com","8.8.8.8","manual",false));
        var preview=(Map<?,?>)service.preview(9L,id);assertEquals(true,preview.get("canAdopt"));
        assertThrows(IllegalArgumentException.class,()->service.adopt(9L,id,1,"stale","pool.example.com"));assertTrue(dns.actions.isEmpty());
        service.adopt(9L,id,1,(String)preview.get("fingerprint"),"pool.example.com");assertEquals(List.of("adopt:8.8.8.8"),dns.actions);
        dns.records.add(new TrafficPoolDns.Record("e".repeat(32),"A","pool.example.com","8.8.4.4","manual",false));
        assertEquals(false,((Map<?,?>)service.preview(9L,id)).get("canAdopt"));
    }
    @Test void domainOwnershipHasAnIndependentDatabaseUniquenessConstraint(){
        create(false);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO traffic_pool(id,name,dns_key,payload) VALUES(?,?,?,?)",UUID.randomUUID().toString(),"different name",AirportSyncState.digest("pool.example.com"),"{}"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool",Integer.class));
    }
}

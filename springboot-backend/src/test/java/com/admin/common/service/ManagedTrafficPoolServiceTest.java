package com.admin.common.service;

import com.admin.common.migration.TrafficPoolMigration;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.VpsHost;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserMapper;
import com.admin.service.NodeService;
import com.admin.service.VpsSshService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedTrafficPoolServiceTest {
    private static final String DOMAIN = "pool.example.com";
    private static final String TOKEN = "isolated_test_token_1234567890";
    private static final String ORIGIN = "https://panel.example.com";
    private static final String PIN = "SHA256:" + "a".repeat(43);

    private final Map<Long,Node> registered = new ConcurrentHashMap<>();
    private final Map<Long,Tunnel> createdTunnels = new ConcurrentHashMap<>();
    private final ForwardMapper forwards = mock(ForwardMapper.class);
    private final TrafficPoolObserver observer = mock(TrafficPoolObserver.class);
    private final VpsSshService ssh = mock(VpsSshService.class);
    private final TrafficPoolDns dns = mock(TrafficPoolDns.class, CALLS_REAL_METHODS);
    private final List<TrafficPoolDns.Record> records = new ArrayList<>();
    private JdbcTemplate jdbc;
    private TrafficPoolService pools;
    private ManagedTrafficPoolService managed;
    private long time;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE node(id BIGINT PRIMARY KEY,server_ip VARCHAR(45),ip VARCHAR(45),ddns_enabled INT,ddns_record_name VARCHAR(255),updated_time BIGINT)");
        jdbc.execute("CREATE TABLE tunnel(id BIGINT PRIMARY KEY,in_ip VARCHAR(255),updated_time BIGINT)");
        jdbc.execute("CREATE TABLE tunnel_entry_node(id BIGINT AUTO_INCREMENT PRIMARY KEY,tunnel_id BIGINT,node_id BIGINT,created_time BIGINT,status INT)");
        new TrafficPoolMigration(jdbc).migrate();
        var tx = new DataSourceTransactionManager(ds);
        NodeMapper nodes = mock(NodeMapper.class);
        TunnelMapper tunnels = mock(TunnelMapper.class);
        UserMapper users = mock(UserMapper.class);
        var admin = new User();
        admin.setId(1L); admin.setRoleId(0); admin.setStatus(1);
        when(users.selectById(1L)).thenReturn(admin);
        var ids = new AtomicLong(10);
        when(nodes.insert(any(Node.class))).thenAnswer(invocation -> {
            Node node = invocation.getArgument(0);
            node.setId(ids.incrementAndGet());
            registered.put(node.getId(),node);
            return jdbc.update("INSERT INTO node(id,server_ip,ip,ddns_enabled) VALUES(?,?,?,0)",
                    node.getId(),node.getServerIp(),node.getIp());
        });
        when(nodes.selectById(anyLong())).thenAnswer(invocation -> registered.get(invocation.getArgument(0)));
        var tunnelIds = new AtomicLong(20);
        when(tunnels.insert(any(Tunnel.class))).thenAnswer(invocation -> {
            Tunnel tunnel = invocation.getArgument(0);
            long id = tunnelIds.incrementAndGet();
            tunnel.setId(id);
            createdTunnels.put(id,tunnel);
            return jdbc.update("INSERT INTO tunnel(id,in_ip) VALUES(?,?)",id,tunnel.getInIp());
        });
        when(tunnels.selectById(anyLong())).thenAnswer(invocation -> createdTunnels.get(invocation.getArgument(0)));
        TunnelIngressNodeResolver ingress = mock(TunnelIngressNodeResolver.class);
        when(ingress.resolveNodeIds(any(Tunnel.class))).thenAnswer(invocation -> {
            Tunnel tunnel = invocation.getArgument(0);
            return new HashSet<>(jdbc.queryForList("SELECT node_id FROM tunnel_entry_node WHERE tunnel_id=?",Long.class,tunnel.getId()));
        });
        when(forwards.selectList(any())).thenReturn(List.of());
        when(observer.addresses(any(Node.class))).thenAnswer(invocation -> List.of(((Node)invocation.getArgument(0)).getServerIp()));
        when(observer.probeAll(anyCollection(),anyInt())).thenAnswer(invocation -> {
            java.util.Collection<String> ips = invocation.getArgument(0);
            return ips.stream().collect(java.util.stream.Collectors.toMap(ip -> ip,ip -> true));
        });
        doReturn("a".repeat(32)).when(dns).findZone(anyString(),anyString());
        doAnswer(invocation -> List.copyOf(records)).when(dns).list(anyString(),anyString(),anyString());
        doAnswer(invocation -> {
            var record = new TrafficPoolDns.Record("b".repeat(32),"A",invocation.getArgument(1),
                    invocation.getArgument(2),invocation.getArgument(4),false);
            records.add(record);
            return record;
        }).when(dns).create(anyString(),anyString(),anyString(),anyInt(),anyString(),anyString());
        doAnswer(invocation -> { records.remove(invocation.getArgument(1,TrafficPoolDns.Record.class)); return null; })
                .when(dns).delete(anyString(),any(),anyString(),anyString(),anyString());
        when(ssh.check(any(),anyString())).thenReturn(new VpsSshService.HealthCheckResult(true,"",0,PIN,false));
        when(ssh.runPoolAgentScript(any(),anyString(),anyString(),anyInt())).thenAnswer(invocation -> {
            assertEquals(PIN,invocation.getArgument(0,VpsHost.class).getSshFingerprint());
            return 0;
        });
        var agents = mock(NodeService.class);
        when(agents.poolAgentInstaller(any(),eq(ORIGIN))).thenReturn("echo reviewed-installer");
        var groups = new NodeGroupService(jdbc,users,nodes,tx);
        pools = spy(new TrafficPoolService(jdbc,groups,nodes,tunnels,forwards,
                mock(ForwardPortReservationService.class),ingress,dns,observer,tx,"test-key"));
        time = System.currentTimeMillis();
        pools.startedAt = time - 150_000;
        managed = new ManagedTrafficPoolService(jdbc,groups,pools,dns,nodes,tunnels,agents,ssh,tx);
    }

    @AfterEach void close() {
        managed.shutdown();
        pools.close();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test void enrollsWithoutVpsInventoryAndBindsTwoServersToOneTunnel() throws Exception {
        assertThrows(SecurityException.class,() -> managed.enroll(2L,enrollment("8.8.4.4")));
        assertThrows(IllegalArgumentException.class,() -> managed.enroll(1L,enrollment("127.0.0.1")));
        assertEquals(0,managed.get(1L).servers().size());
        managed.enroll(1L,enrollment("8.8.4.4"));
        awaitState(11L,"installed");
        managed.enroll(1L,enrollment("1.1.1.1"));
        awaitState(12L,"installed");
        var view = managed.get(1L);
        assertEquals(21L,view.tunnelId());
        assertEquals(1,createdTunnels.size());
        assertEquals(List.of(11L,12L),jdbc.queryForList("SELECT node_id FROM tunnel_entry_node ORDER BY node_id",Long.class));
        assertEquals(2_500_000_000L,view.servers().getFirst().usedBytes());
        assertEquals(200_000_000_000L,view.servers().getFirst().quotaBytes());
        assertEquals(0,pools.list(1L).size());
        assertThrows(IllegalArgumentException.class,() -> managed.enroll(1L,enrollment("8.8.4.4")));
        verify(ssh,times(2)).runPoolAgentScript(any(),eq("fixture-password"),contains("POOL_IDENTITY"),eq(480));
    }

    @Test void installsThenPublishesOnlyAfterForwardReadinessAndRemovesTheLastOfflineIp() throws Exception {
        managed.save(1L,new ManagedTrafficPoolService.Settings(0,DOMAIN,TOKEN));
        managed.enroll(1L,enrollment("8.8.4.4"));
        awaitState(11L,"installed");
        registered.get(11L).setStatus(1);
        when(observer.reported(eq(11L),anyLong())).thenReturn(true);
        doReturn(true).when(pools).rulesReady(any(TrafficPoolState.class),eq(11L));
        cycle(0); cycle(20_000);
        assertEquals("waiting_rules",managed.get(1L).servers().getFirst().status());
        assertTrue(records.isEmpty());
        var forward = new Forward();
        forward.setId(34L); forward.setInPort(18080);
        when(forwards.selectList(any())).thenReturn(List.of(forward));
        cycle(40_000); cycle(60_000);
        assertEquals(List.of("8.8.4.4"),records.stream().map(TrafficPoolDns.Record::content).toList());
        registered.get(11L).setStatus(0);
        cycle(80_000); cycle(100_000); cycle(120_000);
        assertTrue(records.isEmpty());
        assertEquals("offline",managed.get(1L).servers().getFirst().status());
        assertFalse(jdbc.queryForObject("SELECT payload FROM traffic_pool",String.class).contains(TOKEN));
    }

    @Test void failedInstallCanRetryWithoutAllocatingAnotherNode() throws Exception {
        doReturn(42,0).when(ssh).runPoolAgentScript(any(),anyString(),anyString(),eq(480));
        managed.enroll(1L,enrollment("8.8.4.4"));
        awaitState(11L,"failed");
        assertNull(managed.get(1L).tunnelId());
        managed.enroll(1L,enrollment("8.8.4.4"));
        awaitState(11L,"installed");
        assertEquals(1,registered.size());
        assertEquals(1,managed.get(1L).servers().size());
    }

    @Test void createsPoolsThenBatchesAndAppendsOnlyToTheSelectedPool() throws Exception {
        var first=managed.configure(1L,new ManagedTrafficPoolService.PoolSettings("",0,"第一组",DOMAIN,"",TOKEN,null));
        var second=managed.configure(1L,new ManagedTrafficPoolService.PoolSettings("",0,"第二组","other.example.com","",TOKEN,null));
        assertNull(first.tunnelId());
        assertEquals(2,managed.configured(1L).size());
        assertTrue(managed.detail(1L,first.id()).servers().isEmpty());
        var results=managed.enrollBatch(1L,new ManagedTrafficPoolService.Batch(first.id(),
                List.of(enrollment("8.8.4.4"),enrollment("127.0.0.1"),enrollment("8.8.4.4"),enrollment("1.1.1.1"))));
        assertEquals(List.of(true,false,false,true),results.stream().map(ManagedTrafficPoolService.EnrollmentResult::accepted).toList());
        awaitState(11L,"installed"); awaitState(12L,"installed");
        assertEquals(2,managed.detail(1L,first.id()).servers().size());
        assertTrue(managed.detail(1L,second.id()).servers().isEmpty());
        var added=managed.enrollBatch(1L,new ManagedTrafficPoolService.Batch(first.id(),List.of(enrollment("8.8.8.8"))));
        assertTrue(added.getFirst().accepted());
        awaitState(13L,"installed");
        var current=managed.detail(1L,first.id());
        assertEquals(List.of(11L,12L,13L),current.servers().stream().map(ManagedTrafficPoolService.Server::id).toList());
        assertEquals(21L,current.pool().tunnelId());
        assertEquals(1,createdTunnels.size());
        assertFalse(managed.enrollBatch(1L,new ManagedTrafficPoolService.Batch(second.id(),
                List.of(enrollment("8.8.4.4")))).getFirst().accepted());
    }

    @Test void keepsTheNodeAndReconcilesDnsWhenItsBoundAddressChanges() throws Exception {
        var pool=managed.configure(1L,new ManagedTrafficPoolService.PoolSettings("",0,"验证换址",DOMAIN,"",TOKEN,null));
        managed.enrollBatch(1L,new ManagedTrafficPoolService.Batch(pool.id(),List.of(enrollment("8.8.4.4"))));
        awaitState(11L,"installed");
        registered.get(11L).setStatus(1);
        when(observer.reported(eq(11L),anyLong())).thenReturn(true);
        doReturn(true).when(pools).rulesReady(any(TrafficPoolState.class),eq(11L));
        var forward=new Forward();
        forward.setId(34L); forward.setInPort(18080);
        when(forwards.selectList(any())).thenReturn(List.of(forward));
        cycle(pool.id(),0); cycle(pool.id(),20_000);
        assertEquals(List.of("8.8.4.4"),records.stream().map(TrafficPoolDns.Record::content).toList());
        var change=new ManagedTrafficPoolService.AddressChange(pool.id(),11L,"1.1.1.1","root","fixture-password");
        assertThrows(IllegalArgumentException.class,()->managed.changeAddress(1L,change));
        when(observer.probe("1.1.1.1",18080)).thenReturn(true);
        managed.changeAddress(1L,change);
        assertEquals("1.1.1.1",jdbc.queryForObject("SELECT server_ip FROM node WHERE id=11",String.class));
        registered.get(11L).setIp("1.1.1.1");
        registered.get(11L).setServerIp("1.1.1.1");
        cycle(pool.id(),40_000); cycle(pool.id(),60_000);
        assertEquals(new HashSet<>(List.of("8.8.4.4","1.1.1.1")),
                new HashSet<>(records.stream().map(TrafficPoolDns.Record::content).toList()));
        cycle(pool.id(),80_000);
        assertEquals(List.of("1.1.1.1"),records.stream().map(TrafficPoolDns.Record::content).toList());
        assertTrue(managed.detail(1L,pool.id()).servers().getFirst().published());
        verify(ssh,times(1)).runPoolAgentScript(any(),anyString(),contains("POOL_IDENTITY"),eq(480));
    }

    private ManagedTrafficPoolService.Enrollment enrollment(String ip) {
        return new ManagedTrafficPoolService.Enrollment(ip,22,"fixture-password",ORIGIN,2_500_000_000L,200_000_000_000L);
    }

    private void cycle(long elapsed) {
        cycle(TrafficPoolService.MANAGED_ID,elapsed);
    }

    private void cycle(String id,long elapsed) {
        pools.clock = Clock.fixed(Instant.ofEpochMilli(time+elapsed),ZoneOffset.UTC);
        pools.process(id);
    }

    private void awaitState(long id,String expected) throws Exception {
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            if (expected.equals(jdbc.queryForObject("SELECT install_status FROM traffic_pool_host WHERE node_id=?",String.class,id))) return;
            Thread.sleep(20);
        }
        assertEquals(expected,jdbc.queryForObject("SELECT install_status FROM traffic_pool_host WHERE node_id=?",String.class,id));
    }
}

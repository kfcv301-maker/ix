package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeIpHistoryServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final NodeMapper nodes=mock(NodeMapper.class);
    private final UserMapper users=mock(UserMapper.class);
    private final PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
    private final NodeIpHistoryService service=new NodeIpHistoryService(jdbc,nodes,users,new NodeAddressResolver(),tm);
    private User user(long id, int role, int status) { User u=new User();u.setId(id);u.setRoleId(role);u.setStatus(status);return u; }
    private Node node(long id, Long owner) { Node n=new Node();n.setId(id);n.setOwnerUserId(owner);n.setServerIp("node.example");return n; }

    @Test void tunnelAccessNeverGrantsInfrastructureHistoryAccess() {
        when(users.selectById(7L)).thenReturn(user(7,1,1));
        when(nodes.selectById(2L)).thenReturn(node(2,null));
        assertThrows(SecurityException.class,()->service.history(7L,2L,null));
        verifyNoInteractions(jdbc);
        when(nodes.selectById(2L)).thenReturn(node(2,8L));
        assertThrows(SecurityException.class,()->service.history(7L,2L,null));
    }

    @Test void disabledUsersAndMissingNodesAreDeniedBeforeReadingRecords() {
        when(users.selectById(7L)).thenReturn(user(7,0,0));
        assertThrows(SecurityException.class,()->service.summaries(7L));
        when(users.selectById(7L)).thenReturn(user(7,0,1));
        assertThrows(SecurityException.class,()->service.history(7L,2L,null));
        verifyNoInteractions(jdbc);
    }

    @Test void nodeOwnerIsDeniedAndOnlyAdminCanValidateCursor() {
        when(users.selectById(7L)).thenReturn(user(7,1,1));
        when(nodes.selectById(2L)).thenReturn(node(2,7L));
        assertThrows(SecurityException.class,()->service.history(7L,2L,0L));
        assertThrows(SecurityException.class,()->service.history(7L,2L,null));
        assertThrows(SecurityException.class,()->service.summaries(7L));
        when(users.selectById(7L)).thenReturn(user(7,0,1));
        when(nodes.selectById(2L)).thenReturn(node(2,null));
        assertThrows(IllegalArgumentException.class,()->service.history(7L,2L,-1L));
        verifyNoInteractions(jdbc);
    }

    @Test void currentDatabaseRoleRevocationBlocksPreviouslyAuthorizedAdmin() {
        when(users.selectById(7L)).thenReturn(user(7,0,1),user(7,1,1));
        assertEquals(0,service.activeUser(7L).getRoleId());
        assertThrows(SecurityException.class,()->service.summaries(7L));
        verifyNoInteractions(jdbc,nodes);
    }

    @Test void obsoleteObservationCannotOverwriteChangedNodeTarget() {
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",2L)).thenReturn(List.of(Map.of("id",2L)));
        when(nodes.selectById(2L)).thenReturn(node(2,7L));
        service.record(2L,"old.example",new NodeIpObservation.Result("ok","dns",List.of("192.0.2.1")),100_000);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(tm).commit(any());
    }

    @Test void removedNodeIsNotRecreatedByObserver() {
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",2L)).thenReturn(List.of());
        service.record(2L,"node.example",new NodeIpObservation.Result("ok","dns",List.of("192.0.2.1")),100_000);
        verifyNoInteractions(nodes); verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void todayAndSevenDayBoundaryUseShanghaiNotHostTimezone() {
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(Instant.parse("2026-11-02T16:01:00Z"),ZoneOffset.UTC));
        long today=ReflectionTestUtils.invokeMethod(service,"dayStart",0);
        long week=ReflectionTestUtils.invokeMethod(service,"dayStart",6);
        assertEquals(Instant.parse("2026-11-02T16:00:00Z").toEpochMilli(),today);
        assertEquals(Instant.parse("2026-10-27T16:00:00Z").toEpochMilli(),week);
    }

    @Test void slowDnsCannotBlockForwardingSchedulerOrQueueOverlappingBatches() throws Exception {
        NodeAddressResolver resolver=mock(NodeAddressResolver.class);
        NodeIpHistoryService observer=new NodeIpHistoryService(jdbc,nodes,users,resolver,tm);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(nodes.selectList(any(Wrapper.class))).thenReturn(List.of(node(2,null)));
        when(resolver.resolve(anyString())).thenAnswer(call -> {
            entered.countDown(); release.await(5,TimeUnit.SECONDS);
            return new NodeIpObservation.Result("resolve_failed","dns",List.of());
        });
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),observer::observeNodes);
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            observer.observeNodes();
            verify(nodes,times(1)).selectList(any(Wrapper.class));
        } finally { release.countDown(); observer.close(); }
    }
}

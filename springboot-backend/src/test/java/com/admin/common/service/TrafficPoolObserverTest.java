package com.admin.common.service;

import com.admin.entity.Node;
import com.alibaba.fastjson2.JSON;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficPoolObserverTest {
    @Test void onlyFreshCredentialMatchedAwsReportCanReplaceConfiguredV4() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE node_airport_sync(node_id BIGINT PRIMARY KEY,payload TEXT)");
        var resolver=mock(NodeAddressResolver.class);var node=new Node();node.setId(1L);node.setSecret("fixture-secret");node.setServerIp("8.8.4.4");
        when(resolver.resolve("8.8.4.4")).thenReturn(new NodeIpObservation.Result("ok","configured",List.of("8.8.4.4")));
        var observer=new TrafficPoolObserver(resolver,jdbc);var state=new AirportSyncState();state.sourceMode="aws-imds";
        state.reportedSample=new AirportSyncState.Sample("8.8.8.8",System.currentTimeMillis(),"identity",AirportSyncState.digest(node.getSecret()));
        jdbc.update("INSERT INTO node_airport_sync VALUES(1,?)",JSON.toJSONString(state));
        assertEquals(List.of("8.8.8.8"),observer.addresses(node));
        state.reportedSample=new AirportSyncState.Sample("8.8.8.8",System.currentTimeMillis()-31000,"identity",AirportSyncState.digest(node.getSecret()));
        jdbc.update("UPDATE node_airport_sync SET payload=?",JSON.toJSONString(state));assertEquals(List.of("8.8.4.4"),observer.addresses(node));
        state.reportedSample=new AirportSyncState.Sample("8.8.8.8",System.currentTimeMillis(),"identity","different-key");
        jdbc.update("UPDATE node_airport_sync SET payload=?",JSON.toJSONString(state));assertEquals(List.of("8.8.4.4"),observer.addresses(node));
    }
    @Test void privateOrMixedDnsAnswersAreNeverProbedOrPublished() {
        var resolver=mock(NodeAddressResolver.class);var jdbc=mock(JdbcTemplate.class);var node=new Node();node.setId(1L);node.setServerIp("host.example.com");
        when(resolver.resolve(anyString())).thenReturn(new NodeIpObservation.Result("ok","dns",List.of("8.8.8.8","127.0.0.1")));
        var observer=new TrafficPoolObserver(resolver,jdbc);assertTrue(observer.addresses(node).isEmpty());assertFalse(observer.probe("169.254.169.254",80));
    }
}

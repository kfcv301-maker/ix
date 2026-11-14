package com.admin.common.service;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrafficPoolObserverTest {
    final TrafficPoolObserver observer=new TrafficPoolObserver();

    @Test void onlyBoundNumericServerAddressCanBeProbed() {
        var node=new Node();node.setServerIp(" 8.8.4.4 ");node.setIp("1.1.1.1");node.setDdnsEnabled(1);node.setDdnsRecordName("cloudflare.com");
        assertEquals(List.of("8.8.4.4"),observer.addresses(node));
        node.setServerIp("cloudflare.com");assertTrue(observer.addresses(node).isEmpty());
        node.setServerIp(null);assertTrue(observer.addresses(node).isEmpty());
        node.setServerIp("2606:4700:4700::1111");assertEquals(List.of("2606:4700:4700:0:0:0:0:1111"),observer.addresses(node));
    }

    @Test void internalAndNonLiteralValuesAreNeverProbedOrPublished() {
        var node=new Node();
        for(String ip:List.of("127.0.0.1","169.254.169.254","fd00::2","8.8.8.8:10000","::ffff:192.168.1.2")){
            node.setServerIp(ip);assertTrue(observer.addresses(node).isEmpty(),ip);
        }
        assertFalse(observer.probe("169.254.169.254",80));
        assertFalse(observer.probe("cloudflare.com",443));
    }
}

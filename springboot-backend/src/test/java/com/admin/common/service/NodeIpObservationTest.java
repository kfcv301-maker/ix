package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NodeIpObservationTest {
    private NodeIpObservation.Result ok(String... values) { return new NodeIpObservation.Result("ok","dns",List.of(values)); }
    private NodeIpObservation.State baseline() {
        var state=new NodeIpObservation.State();
        assertEquals("BASELINE",NodeIpObservation.accept(state,"node.example",ok("192.0.2.1"),100_000).kind());
        return state;
    }

    @Test void initialSnapshotAndRestartDoNotCountAsChanges() {
        var state=JSON.parseObject(JSON.toJSONString(baseline()),NodeIpObservation.State.class);
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.1"),160_000));
        assertEquals(0,state.getLastChangeAt());
        assertEquals(100_000,state.getStartedAt());
    }

    @Test void reorderedOrDuplicateAnswersDoNotCreateEvents() {
        var state=new NodeIpObservation.State();
        NodeIpObservation.accept(state,"node.example",ok("2001:db8::1","192.0.2.1"),100_000);
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.1","2001:db8::1","192.0.2.1"),160_000));
    }

    @Test void changeWaitsForSettlementWindowAndTwoIndependentSamples() {
        var state=baseline();
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.2","2001:db8::2"),160_000));
        assertEquals("confirming",state.getStatus());
        assertEquals(List.of("192.0.2.1"),state.getAddresses());
        assertNull(NodeIpObservation.accept(state,"node.example",ok("2001:db8::2","192.0.2.2"),220_000));
        var event=NodeIpObservation.accept(state,"node.example",ok("2001:db8::2","192.0.2.2"),280_000);
        assertEquals("CHANGE",event.kind()); assertEquals(160_000,event.observedAt()); assertEquals(280_000,event.confirmedAt());
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.2","2001:db8::2"),340_000));
    }

    @Test void transientErrorsClearCandidatesAndNeverEraseKnownAddresses() {
        for (String failure:List.of("resolve_failed","no_records","invalid_answer")) {
            var state=baseline();
            NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),160_000);
            assertNull(NodeIpObservation.accept(state,"node.example",new NodeIpObservation.Result(failure,"dns",List.of()),220_000));
            assertEquals(List.of("192.0.2.1"),state.getAddresses());
            assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),280_000));
            assertEquals("CHANGE",NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),400_000).kind());
        }
    }

    @Test void briefDnsFlapAndDelayedResultsCannotInventAChange() {
        var state=baseline();
        NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),160_000);
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),161_000));
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.1"),220_000));
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.2"),150_000));
        assertEquals(0,state.getLastChangeAt());
    }

    @Test void changingMonitoredDomainRebaselinesWithoutCounting() {
        var state=baseline();
        var event=NodeIpObservation.accept(state,"other.example",ok("192.0.2.9"),160_000);
        assertEquals("TARGET_CHANGED",event.kind());
        assertEquals(0,state.getLastChangeAt());
    }

    @Test void missingFamilyNeverErasesCompleteBaselineOrCreatesChange() {
        var state=new NodeIpObservation.State();
        NodeIpObservation.accept(state,"node.example",ok("192.0.2.1","2001:db8::1"),100_000);
        NodeIpObservation.accept(state,"node.example",ok("192.0.2.1"),160_000);
        assertNull(NodeIpObservation.accept(state,"node.example",ok("192.0.2.1"),400_000));
        assertEquals("incomplete_families",state.getStatus());
        assertNull(NodeIpObservation.accept(state,"node.example",ok(),460_000));
        assertEquals("no_records",state.getStatus()); assertEquals(List.of("192.0.2.1","2001:db8::1"),state.getAddresses());
    }

    @Test void resolverRejectsUrlsAndNormalizesLiteralAddressesWithoutDns() {
        var resolver=new NodeAddressResolver();
        assertEquals("invalid_target",resolver.resolve("https://example.invalid/private?key=x").status());
        assertEquals("invalid_target",resolver.resolve("999.1.2.3").status());
        assertEquals("invalid_target",resolver.resolve("foo bar.invalid").status());
        assertEquals("invalid_target",resolver.resolve("").status());
        assertEquals(List.of("192.0.2.1"),resolver.resolve("192.000.002.001").addresses());
        assertEquals("configured",resolver.resolve("[2001:db8::1]").source());
        assertEquals(resolver.resolve("2001:0db8:0:0:0:0:0:1").addresses(),resolver.resolve("2001:db8::1").addresses());
    }
}

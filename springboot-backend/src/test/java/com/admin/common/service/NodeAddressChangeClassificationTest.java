package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NodeAddressChangeClassificationTest {
    private static final String TARGET="node.example";
    private NodeIpObservation.Result ok(String... addresses) {
        return new NodeIpObservation.Result("ok","dns",List.of(addresses));
    }
    private NodeIpObservation.State baseline() {
        var state=new NodeIpObservation.State();
        NodeIpObservation.accept(state,TARGET,ok("192.0.2.1","2001:db8::1"),100_000);
        return state;
    }
    private NodeContinuityObservation.Evidence classify(List<String> before,List<String> after) {
        return NodeAddressChangeClassification.classify(new NodeIpObservation.Event("CHANGE",before,after,160_000,280_000),
                new NodeContinuityObservation.State(),100_000,280_000);
    }

    @Test void ipv4RotationDoesNotRequireNewAgentMetrics() {
        var result=classify(List.of("192.0.2.1","2001:db8::1"),List.of("192.0.2.2","2001:db8::1"));
        assertEquals("IP_ONLY_LIKELY",result.classification());
        assertEquals("V4_ONLY_CHANGED",result.reason());
    }
    @Test void bothFamiliesChangingIndicatesReplacementEvenWithoutMetrics() {
        var result=classify(List.of("192.0.2.1","2001:db8::1"),List.of("192.0.2.2","2001:db8::2"));
        assertEquals("MACHINE_SUSPECTED",result.classification());
        assertEquals("DUAL_STACK_CHANGED",result.reason());
    }
    @Test void incompleteFamiliesOrIpv6OnlyChangesRemainUnknown() {
        for (var after:List.of(List.of("192.0.2.2"),List.of("2001:db8::2"),List.of("192.0.2.1","2001:db8::2")))
            assertEquals("UNKNOWN",classify(List.of("192.0.2.1","2001:db8::1"),after).classification());
        assertEquals("UNKNOWN",classify(List.of("192.0.2.1"),List.of("192.0.2.2","2001:db8::2")).classification());
    }
    @Test void eitherFamilyCanArriveFirstAndStillProduceOnlyOneEvent() {
        for (boolean v4First:List.of(true,false)) {
            var state=baseline();
            var intermediate=v4First ? ok("192.0.2.2","2001:db8::1") : ok("192.0.2.1","2001:db8::2");
            assertNull(NodeIpObservation.accept(state,TARGET,intermediate,160_000));
            assertNull(NodeIpObservation.accept(state,TARGET,intermediate,220_000));
            assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),280_000));
            var event=NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),340_000);
            assertEquals(160_000,event.observedAt());
            assertEquals(List.of("192.0.2.1","2001:db8::1"),event.previous());
            assertEquals("MACHINE_SUSPECTED",classify(event.previous(),event.addresses()).classification());
            assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),400_000));
        }
    }
    @Test void persistedWindowSurvivesPanelRestartWithoutSplittingTheEvent() {
        var state=baseline();
        NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::1"),160_000);
        state=JSON.parseObject(JSON.toJSONString(state),NodeIpObservation.State.class);
        assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),220_000));
        var event=NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),280_000);
        assertNotNull(event);assertEquals(160_000,event.observedAt());
    }
    @Test void temporaryMissingIpv6DoesNotEraseTheComparisonBaseline() {
        var state=baseline();
        NodeIpObservation.accept(state,TARGET,ok("192.0.2.2"),160_000);
        assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2"),340_000));
        assertEquals("incomplete_families",state.getStatus());
        assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),400_000));
        var event=NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::2"),520_000);
        assertEquals("MACHINE_SUSPECTED",classify(event.previous(),event.addresses()).classification());
    }
    @Test void legacyCandidateAndHistoricalCountersStayCompatible() {
        var state=baseline();
        state.setCandidate(List.of("192.0.2.2","2001:db8::1"));state.setCandidateSince(160_000);
        assertNull(NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::1"),220_000));
        assertEquals(160_000,NodeIpObservation.accept(state,TARGET,ok("192.0.2.2","2001:db8::1"),280_000).observedAt());
        assertEquals("machineSuspected",NodeAddressChangeClassification.counter("CHANGE","MACHINE_SUSPECTED","DUAL_STACK_CHANGED"));
        assertEquals("ipNearMachine",NodeAddressChangeClassification.counter("CHANGE","MACHINE_SUSPECTED","NEAR_RECONNECT"));
        assertEquals("machineSuspected",NodeAddressChangeClassification.counter("MACHINE_SUSPECTED","MACHINE_SUSPECTED","BOOT_CHANGED"));
        assertEquals("unknownIp",NodeAddressChangeClassification.counter("CHANGE",null,null));
    }
}

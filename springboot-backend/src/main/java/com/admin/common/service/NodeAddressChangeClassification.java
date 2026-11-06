package com.admin.common.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Deployment rule: IPv4 rotation keeps IPv6; replacing a host changes both. */
public final class NodeAddressChangeClassification {
    private NodeAddressChangeClassification() {}

    private static Set<String> family(List<String> addresses, boolean ipv6) {
        return addresses.stream().filter(a -> a.contains(":") == ipv6).collect(Collectors.toSet());
    }

    static boolean dualStack(List<String> addresses) {
        return !family(addresses, false).isEmpty() && !family(addresses, true).isEmpty();
    }

    public static NodeContinuityObservation.Evidence classify(NodeIpObservation.Event event,
            NodeContinuityObservation.State continuity, long evidenceStart, long now) {
        if (!dualStack(event.previous()) || !dualStack(event.addresses()))
            return evidence("UNKNOWN", "INCOMPLETE_ADDRESS_FAMILIES");
        boolean v4 = !family(event.previous(), false).equals(family(event.addresses(), false));
        boolean v6 = !family(event.previous(), true).equals(family(event.addresses(), true));
        if (v4 && v6) {
            var runtime = NodeContinuityObservation.classifyIp(continuity, evidenceStart, now);
            return evidence("MACHINE_SUSPECTED", "MACHINE_SUSPECTED".equals(runtime.classification())
                    ? "DUAL_STACK_CHANGED_RESTART" : "DUAL_STACK_CHANGED");
        }
        if (v4) return evidence("IP_ONLY_LIKELY", "V4_ONLY_CHANGED");
        return evidence("UNKNOWN", "V6_ONLY_CHANGED");
    }

    private static NodeContinuityObservation.Evidence evidence(String classification, String reason) {
        return new NodeContinuityObservation.Evidence(classification, reason, 0, 0);
    }

    /** Keep old recorded judgments, but count each new dual-stack event only once. */
    static String counter(String kind, String classification, String reason) {
        if ("MACHINE_SUSPECTED".equals(kind)) return "machineSuspected";
        if ("IP_ONLY_LIKELY".equals(classification)) return "ipOnlyLikely";
        if ("MACHINE_SUSPECTED".equals(classification))
            return reason != null && reason.startsWith("DUAL_STACK_CHANGED") ? "machineSuspected" : "ipNearMachine";
        return "unknownIp";
    }
}

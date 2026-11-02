package com.admin.common.service;

import lombok.Data;
import java.util.List;
import java.util.Objects;

/** DNS observations are not an assertion that the provider accepted a DDNS write. */
public final class NodeIpObservation {
    private NodeIpObservation() {}

    @Data
    public static class State {
        private String target = "";
        private String source = "dns";
        private List<String> addresses = List.of();
        private List<String> candidate = List.of();
        private long candidateSince;
        private long startedAt;
        private long baselineAt;
        private long checkedAt;
        private long resolvedAt;
        private long lastChangeAt;
        private String status = "pending";
    }

    public record Result(String status, String source, List<String> addresses) {}
    public record Event(String kind, List<String> previous, List<String> addresses, long observedAt, long confirmedAt) {}

    public static Event accept(State state, String target, Result result, long now) {
        if (now <= state.checkedAt) return null; // Ignore delayed or duplicate observations.
        if (state.startedAt == 0) state.startedAt = now;
        boolean newTarget = !Objects.equals(state.target, target);
        boolean hadBaseline = state.baselineAt > 0;
        if (newTarget) {
            state.target = target;
            state.addresses = List.of();
            state.candidate = List.of();
            state.candidateSince = 0;
            state.baselineAt = 0;
        }
        state.checkedAt = now;
        state.source = result.source();
        state.status = result.status();
        if (!"ok".equals(result.status()) || result.addresses().isEmpty()) {
            state.candidate = List.of();
            state.candidateSince = 0;
            if ("ok".equals(result.status())) state.status = "no_records";
            return null; // Failure is never an address removal or a change.
        }
        List<String> addresses = result.addresses().stream().distinct().sorted().toList();
        state.resolvedAt = now;
        if (state.baselineAt == 0) {
            state.baselineAt = now;
            state.addresses = addresses;
            return new Event(newTarget && hadBaseline ? "TARGET_CHANGED" : "BASELINE", List.of(), addresses, now, now);
        }
        if (state.addresses.equals(addresses)) {
            state.candidate = List.of(); state.candidateSince = 0;
            return null;
        }
        if (!state.candidate.equals(addresses)) {
            state.candidate = addresses; state.candidateSince = now; state.status = "confirming";
            return null;
        }
        if (now - state.candidateSince < 30_000) { state.status = "confirming"; return null; }
        Event event = new Event("CHANGE", state.addresses, addresses, state.candidateSince, now);
        state.addresses = addresses; state.lastChangeAt = now;
        state.candidate = List.of(); state.candidateSince = 0;
        return event;
    }
}

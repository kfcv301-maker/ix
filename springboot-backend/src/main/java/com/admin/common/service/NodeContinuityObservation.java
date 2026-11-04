package com.admin.common.service;

import lombok.Data;
import java.util.Objects;

/** Passive inference only: uptime/configuration cannot establish machine identity. */
public final class NodeContinuityObservation {
    private NodeContinuityObservation() {}
    static final long MAX_GAP = 900_000, TOLERANCE = 30_000;
    public record Sample(String epoch, String session, long receivedAt, long uptime,
                         int cores, long memory, long disk) {}
    public record Evidence(String classification, String reason, long beforeUptime, long afterUptime) {}
    public record Event(String kind, long observedAt, long confirmedAt, Evidence evidence) {}
    @Data public static class State {
        private Sample last;
        private Sample candidate;
        private long continuousSince;
        private long lastSuspectedAt;
        private long lastSuspectedObservedAt;
        private String status = "unavailable";
        private String reason = "NO_METRICS";
    }

    public static Event accept(State s, Sample next, long now) {
        if (!valid(next, now)) {
            s.continuousSince=0; s.candidate=null; s.status="unavailable"; s.reason="NO_METRICS";
            return null;
        }
        Sample old=s.last;
        if (old==null || !Objects.equals(old.epoch(), next.epoch())) {
            s.lastSuspectedAt=0; s.lastSuspectedObservedAt=0;
            baseline(s,next,old==null ? "FIRST_SAMPLE" : "PANEL_RESTART"); return null;
        }
        if (next.receivedAt()<=old.receivedAt()) return null;
        if (next.receivedAt()-old.receivedAt()>MAX_GAP) {
            baseline(s,next,"OBSERVATION_GAP");
            return new Event("OBSERVATION_GAP",next.receivedAt(),now,
                    new Evidence("UNKNOWN","OBSERVATION_GAP",old.uptime(),next.uptime()));
        }
        if (Objects.equals(old.session(),next.session())) {
            s.candidate=null;
            if (!sameBoot(old,next)) {
                baseline(s,next,"INCONSISTENT_UPTIME"); return null;
            }
            if (s.continuousSince==0) s.continuousSince=next.receivedAt();
            s.last=next; s.status="continuous"; s.reason="UPTIME_CONTINUOUS"; return null;
        }
        Sample first=s.candidate;
        if (first==null || !Objects.equals(first.session(),next.session())) {
            s.candidate=next; s.status="confirming"; s.reason="RECONNECT_PENDING";
            // Retain the older anchor while a new connection is being confirmed.
            return null;
        }
        if (next.receivedAt()<=first.receivedAt() || next.receivedAt()-first.receivedAt()<TOLERANCE) return null;
        boolean stable=sameBoot(first,next);
        boolean changed=hardwareChanged(old,first) && hardwareChanged(old,next);
        boolean bootChanged=!sameBoot(old,first) && !sameBoot(old,next);
        String classification=!stable ? "UNKNOWN" : changed || bootChanged ? "MACHINE_SUSPECTED" : "CONTINUOUS";
        String reason=!stable ? "INCONSISTENT_UPTIME" : changed ? "CONFIG_CHANGED" : bootChanged ? "BOOT_CHANGED" : "UPTIME_CONTINUOUS";
        s.last=next; s.candidate=null; s.reason=reason;
        s.status="CONTINUOUS".equals(classification) ? "continuous" : "MACHINE_SUSPECTED".equals(classification) ? "suspected" : "unknown";
        if (!"CONTINUOUS".equals(classification)) s.continuousSince=next.receivedAt();
        else if (s.continuousSince==0) s.continuousSince=next.receivedAt();
        if ("MACHINE_SUSPECTED".equals(classification)) {
            s.lastSuspectedAt=now; s.lastSuspectedObservedAt=first.receivedAt();
        }
        return new Event("MACHINE_SUSPECTED".equals(classification) ? "MACHINE_SUSPECTED" : "RECONNECTED",
                first.receivedAt(),now,new Evidence(classification,reason,old.uptime(),next.uptime()));
    }

    public static Evidence classifyIp(State s, long windowStart, long now) {
        if (windowStart<=0) return new Evidence("UNKNOWN","NO_BASELINE",0,0);
        if (s.lastSuspectedObservedAt>=windowStart && s.lastSuspectedAt<=now && now-s.lastSuspectedAt<=MAX_GAP)
            return new Evidence("MACHINE_SUSPECTED","NEAR_RECONNECT",0,0);
        if ("continuous".equals(s.status) && valid(s.last,now) && s.continuousSince>0 && s.continuousSince<=windowStart)
            return new Evidence("IP_ONLY_LIKELY","UPTIME_CONTINUOUS",0,0);
        return new Evidence("UNKNOWN",s.reason,0,0);
    }
    private static boolean valid(Sample s,long now) {
        return s!=null && s.epoch()!=null && s.session()!=null && s.receivedAt()>0
                && s.receivedAt()<=now && now-s.receivedAt()<=30_000 && s.uptime()>0 && s.uptime()<315_576_000L;
    }
    private static boolean sameBoot(Sample a,Sample b) {
        return Math.abs((b.receivedAt()-a.receivedAt())-(b.uptime()-a.uptime())*1000)<=TOLERANCE;
    }
    private static boolean hardwareChanged(Sample a,Sample b) {
        return a.cores()>0 && b.cores()>0 && a.cores()!=b.cores()
                || different(a.memory(),b.memory()) || different(a.disk(),b.disk());
    }
    private static boolean different(long a,long b) { return a>0 && b>0 && Math.abs((double)a-b)/Math.max(a,b)>0.10; }
    private static void baseline(State s,Sample sample,String reason) {
        s.last=sample; s.candidate=null; s.continuousSince=sample.receivedAt(); s.status="unknown"; s.reason=reason;
    }
}

package com.admin.common.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Persisted latest-event outbox. A new transport must establish its own stable baseline. */
public class AirportSyncState {
    public String endpoint="", bindingId="", encryptedToken="";
    public String sourceMode="peer";
    public Sample reportedSample;
    public long reportObservedAt;
    public boolean enabled;
    public long revision;
    public String candidate="", connection="", pendingIp="", lastIp="", status="disabled";
    public long firstAt,lastSampleAt,pendingAt,lastSuccessAt,nextAttemptAt;
    public int attempts;
    public boolean blocked;
    public record Sample(String ip,long observedAt,String connection,String credentialDigest) { }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Digest unavailable"); }
    }
    public boolean observe(Sample sample,long now) {
        if (!enabled || blocked) return false;
        if (sample==null || !AirportAddressPolicy.publicV4(sample.ip()) || now<sample.observedAt() || now-sample.observedAt()>30_000) {
            candidate=""; connection=""; firstAt=0; status="waiting_source"; return false;
        }
        if (!Objects.equals(candidate,sample.ip()) || !Objects.equals(connection,sample.connection())) {
            candidate=sample.ip(); connection=sample.connection(); firstAt=sample.observedAt(); lastSampleAt=sample.observedAt();
            pendingIp=""; pendingAt=0; attempts=0; nextAttemptAt=0; status="confirming"; return false;
        }
        if (sample.observedAt()<lastSampleAt) return false;
        boolean newReport=sample.observedAt()>lastSampleAt;
        lastSampleAt=sample.observedAt();
        if (sample.observedAt()-firstAt<10_000) return false;
        if (!pendingIp.isEmpty() && now-pendingAt>240_000) { pendingIp=""; pendingAt=0; }
        if (pendingIp.isEmpty()) {
            if (!newReport) return false;
            if (sample.ip().equals(lastIp) && now-lastSuccessAt<60_000) { status="synced";return false; }
            pendingIp=sample.ip(); pendingAt=sample.observedAt(); attempts=0; nextAttemptAt=0; status="pending";
        }
        return now>=nextAttemptAt;
    }
    public void completed(String result,long now) {
        if ("synced".equals(result)) {
            lastIp=pendingIp;lastSuccessAt=now;pendingIp="";pendingAt=0;attempts=0;nextAttemptAt=0;status="synced";
        } else {
            status=result;
            if (result.startsWith("blocked_")) { blocked=true;return; }
            attempts=Math.min(attempts+1,20);
            nextAttemptAt=now+Math.min(60_000,5_000L*(1L<<Math.min(attempts-1,4)));
        }
    }
}

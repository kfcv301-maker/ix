package com.admin.common.service;

import java.util.*;

/** Persisted fence for honest replacement machines sharing a stable installation credential.
 * Instance metadata is reported by the authenticated node; it is not AWS attestation.
 */
public class AirportObserverIdentity {
    public String active="",credentialDigest="";
    public long activeSeenAt,remoteAt;
    public Map<String,Long> contenders=new LinkedHashMap<>();
    public Set<String> retired=new LinkedHashSet<>();
    public boolean conflict,capacityExceeded;
    static final long QUIET=20_000;
    public boolean accept(String identity,String digest,long observedAt,long now) {
        if (!credentialDigest.isEmpty() && !credentialDigest.equals(digest)) {
            active="";activeSeenAt=0;remoteAt=0;contenders.clear();retired.clear();conflict=false;capacityExceeded=false;
        }
        credentialDigest=digest;
        if (capacityExceeded || retired.contains(identity)) return false;
        if (active.isEmpty()) active=identity;
        if (identity.equals(active)) {
            if (observedAt<=remoteAt) return false;
            remoteAt=observedAt;activeSeenAt=now;
        } else {
            if (!contenders.containsKey(identity) && contenders.size()+retired.size()>=256) {
                capacityExceeded=true;conflict=true;return false;
            }
            contenders.put(identity,now);
        }
        boolean otherLive=contenders.entrySet().stream().anyMatch(e->!e.getKey().equals(identity) && now-e.getValue()<QUIET);
        if (!identity.equals(active) && now-activeSeenAt<QUIET) otherLive=true;
        if (otherLive) { conflict=true;return false; }
        // A single source has survived the quiet period. Retire every other observed identity.
        if (!identity.equals(active)) {
            retired.add(active);active=identity;activeSeenAt=now;remoteAt=observedAt;
        }
        for (String previous:contenders.keySet()) if (!previous.equals(active)) retired.add(previous);
        contenders.clear();conflict=false;return true;
    }
}

package com.admin.common.service;

import java.time.*;
import java.util.*;

public class TrafficPoolState {
    public String id="",name="",mode="individual",direction="both",domain="",zoneId="",encryptedToken="",dnsStatus="disabled";
    public long revision,quotaBytes,checkedAt,lastSyncedAt;
    public int resetDay=1,probePort=443,failureThreshold=3,recoveryThreshold=2,ttl=60;
    public boolean dnsEnabled,keepLastAddress,automaticProbe;
    public List<Long> tunnelIds=new ArrayList<>();
    public PreviousDns previousDns;
    public List<String> publishedAddresses;
    public record PreviousDns(String domain, String zoneId, String encryptedToken) { }
    public static class Member {
        public long nodeId,quotaBytes,usedBytes,lastRx,lastTx,lastAt,bootAt;
        public String cycle="",status="waiting",address="";
        public boolean enabled=true;
        public Map<String,Health> health=new TreeMap<>();
        public long lastProbeAt;
        public void account(TrafficSample s,long now,int resetDay,String direction) {
            String current=cycle(now,resetDay);
            boolean reset=!cycle.isEmpty() && !cycle.equals(current);
            if(reset) {usedBytes=0;lastAt=0;}
            if(s==null || s.receivedAt<=lastAt || s.rx<0 || s.tx<0 || s.uptime<0) { cycle=current;return; }
            long boot=s.receivedAt-s.uptime*1000;
            if(!reset && lastAt>0 && Math.abs(boot-bootAt)<30000 && s.rx>=lastRx && s.tx>=lastTx) {
                long delta=switch(direction) { case "out"->s.tx-lastTx;case "in"->s.rx-lastRx;default->Math.addExact(s.rx-lastRx,s.tx-lastTx); };
                usedBytes=Math.addExact(usedBytes,delta);
            }
            cycle=current;lastRx=s.rx;lastTx=s.tx;lastAt=s.receivedAt;bootAt=boot;
        }
    }
    public record TrafficSample(long receivedAt,long uptime,long rx,long tx) { }
    public static class Health {
        public boolean eligible;
        public int failures,successes;
        public long checkedAt, tcpCheckedAt;
        public int tcpPort;
        public Long tcpElapsedMs;
        public String tcpResult;
        public void observe(boolean success,long now,int fail,int recover) {
            if(now<=checkedAt || checkedAt>0 && now-checkedAt<15000) return;
            checkedAt=now;
            if(success) { failures=0;successes=Math.min(successes+1,recover);if(successes>=recover)eligible=true; }
            else { successes=0;failures=Math.min(failures+1,fail);if(failures>=fail)eligible=false; }
        }
    }
    public static String cycle(long now,int resetDay) {
        var date=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var month=YearMonth.from(date);var start=month.atDay(Math.min(resetDay,month.lengthOfMonth()));
        if(date.isBefore(start)) { month=month.minusMonths(1);start=month.atDay(Math.min(resetDay,month.lengthOfMonth())); }
        return start.toString();
    }
}

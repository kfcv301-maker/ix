package com.admin.common.service;

import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class TrafficPoolObserver {
    private final NodeAddressResolver resolver;
    private final JdbcTemplate jdbc;
    public TrafficPoolObserver(NodeAddressResolver resolver,JdbcTemplate jdbc) { this.resolver=resolver;this.jdbc=jdbc; }
    public TrafficPoolState.TrafficSample metrics(long node,long now) { return WebSocketServer.trafficPoolSample(node,now); }
    public List<String> addresses(Node node) {
        long now=System.currentTimeMillis();
        String reported=""; // A generic outbound/host metric is not proof of an ingress address.
        var observations=jdbc.query("SELECT payload FROM node_airport_sync WHERE node_id=?",(r,n)->com.alibaba.fastjson2.JSON.parseObject(r.getString(1),AirportSyncState.class),node.getId());
        if(!observations.isEmpty()) {
            var sample=observations.getFirst().reportedSample;
            if("aws-imds".equals(observations.getFirst().sourceMode) && sample!=null && node.getSecret()!=null && AirportSyncState.digest(node.getSecret()).equals(sample.credentialDigest()) && now>=sample.observedAt()
                    && now-sample.observedAt()<=30000 && AirportAddressPolicy.publicV4(sample.ip()))reported=sample.ip();
        }
        var result=resolver.resolve(NodeAddressResolver.target(node));
        if(!"ok".equals(result.status()))return reported.isEmpty()?List.of():List.of(reported);
        var ips=new TreeSet<String>();
        for(String ip:result.addresses()) {
            var address=AirportAddressPolicy.literal(ip);
            if(address==null || !AirportAddressPolicy.publicAddress(address))return List.of();
            ips.add(address.getHostAddress());
        }
        if(!reported.isEmpty()) {
            boolean ipv6=reported.contains(":");ips.removeIf(ip->ip.contains(":")==ipv6);ips.add(reported);
        }
        return ips.size()>8?List.of():List.copyOf(ips);
    }
    public boolean probe(String ip,int port) {
        var address=AirportAddressPolicy.literal(ip);
        if(address==null || !AirportAddressPolicy.publicAddress(address))return false;
        try(var socket=new Socket()) { socket.connect(new InetSocketAddress(address,port),1500);return true; }
        catch(Exception failed) { return false; }
    }
    /** Probe in parallel with bounded sockets, so one unreachable member cannot stall every peer. */
    public Map<String,Boolean> probeAll(Collection<String> addresses,int port) {
        var result=new ConcurrentHashMap<String,Boolean>();var limit=new Semaphore(32);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<?>>();
            for(String ip:addresses)tasks.add(executor.submit(()->{
                boolean acquired=false;
                try {limit.acquire();acquired=true;result.put(ip,probe(ip,port));}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                finally{if(acquired)limit.release();}
            }));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            for(var task:tasks)try{task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(Exception timeout){task.cancel(true);}
        }
        return result; // Missing results are unknown, never recorded as probe failures.
    }
}

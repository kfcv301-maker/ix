package com.admin.common.service;

import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class TrafficPoolObserver {
    public TrafficPoolState.TrafficSample metrics(long node,long now) { return WebSocketServer.trafficPoolSample(node,now); }
    public boolean reported(long node,long now) { return WebSocketServer.continuitySample(node,now)!=null; }
    public List<String> addresses(Node node) {
        // Agent reports prove liveness. Only the administrator's bound server IP is an ingress target.
        String ip=node.getServerIp();
        var address=AirportAddressPolicy.literal(ip==null?null:ip.trim());
        return address!=null && AirportAddressPolicy.publicAddress(address)?List.of(address.getHostAddress()):List.of();
    }
    public record Probe(boolean reachable, Long elapsedMs, String result) { }

    public boolean probe(String ip,int port) {
        return checkPort(ip,port).reachable();
    }

    Probe checkPort(String ip,int port) {
        var address=AirportAddressPolicy.literal(ip);
        if(address==null || !AirportAddressPolicy.publicAddress(address)) return new Probe(false,null,"invalid");
        long started=System.nanoTime();
        String result;
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress(address,port),1500);
            result="reachable";
        } catch(SocketTimeoutException timeout) { result="timeout"; }
        catch(ConnectException refused) { result="connect_error"; }
        catch(java.io.IOException failed) { result="network_error"; }
        return new Probe("reachable".equals(result),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),result);
    }
    /** Probe in parallel with bounded sockets, so one unreachable member cannot stall every peer. */
    public Map<String,Probe> probeAll(Collection<String> addresses,int port) {
        var result=new ConcurrentHashMap<String,Probe>();var limit=new Semaphore(32);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<?>>();
            for(String ip:addresses)tasks.add(executor.submit(()->{
                boolean acquired=false;
                try {limit.acquire();acquired=true;result.put(ip,checkPort(ip,port));}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                finally{if(acquired)limit.release();}
            }));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            for(var task:tasks)try{task.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(Exception timeout){task.cancel(true);}
        }
        return result; // Missing results are unknown, never recorded as probe failures.
    }
}

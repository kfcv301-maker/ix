package com.admin.common.service;

import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@DependsOn("airportSyncMigration")
public class AirportSyncService {
    private final JdbcTemplate jdbc;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final AirportSyncTransport transport;
    private final TransactionTemplate transactions;
    private final String key;
    private final ExecutorService workers=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"airport-sync");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    private long cursor;
    Clock clock=Clock.systemUTC();
    public AirportSyncService(JdbcTemplate jdbc,NodeMapper nodes,UserMapper users,AirportSyncTransport transport,
                              PlatformTransactionManager manager,@Value("${jwt-secret}") String key) {
        this.jdbc=jdbc;this.nodes=nodes;this.users=users;this.transport=transport;
        this.transactions=new TransactionTemplate(manager);this.key=key;
    }
    public record Settings(Long nodeId,boolean enabled,String endpoint,String bindingId,String token,Long revision,String sourceMode) {
        @Override public String toString() { return "AirportSyncSettings[redacted]"; }
    }
    private void admin(Long userId) {
        User user=userId==null ? null : users.selectById(userId);
        if (user==null || !Objects.equals(user.getRoleId(),0) || !Objects.equals(user.getStatus(),1))
            throw new SecurityException("仅管理员可管理机场同步");
    }
    private Node node(Long id) {
        Node node=id==null ? null : nodes.selectById(id);
        if (node==null) throw new IllegalArgumentException("节点不存在");
        return node;
    }
    private AirportSyncState load(long id,boolean lock) {
        List<String> rows=jdbc.query("SELECT payload FROM node_airport_sync WHERE node_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->rs.getString(1),id);
        return rows.isEmpty() ? null : JSON.parseObject(rows.getFirst(),AirportSyncState.class);
    }
    private AESCrypto crypto(long id,String endpoint,String binding) {
        return new AESCrypto(key+":airport-sync:v1:"+id+":"+endpoint+":"+binding);
    }
    public Map<String,Object> get(Long userId,Long id) {
        admin(userId);node(id);var state=load(id,false);admin(userId);
        return view(id,state==null ? new AirportSyncState() : state);
    }
    public Map<String,Object> save(Long userId,Settings settings) {
        admin(userId);node(settings.nodeId());
        if (settings.revision()==null || settings.revision()<0) throw new IllegalArgumentException("请刷新设置后重试");
        String endpoint=AirportAddressPolicy.endpoint(settings.endpoint());
        String binding=settings.bindingId()==null ? "" : settings.bindingId().trim().toLowerCase(Locale.ROOT);
        if (!binding.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IllegalArgumentException("请输入有效的绑定 ID");
        String token=settings.token()==null ? "" : settings.token();
        if (!Set.of("peer","aws-imds").contains(settings.sourceMode()==null?"":settings.sourceMode())) throw new IllegalArgumentException("请选择地址来源");
        if (!token.isEmpty() && !token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("绑定密钥必须为机场生成的 64 位小写十六进制密钥");
        try {
            return transactions.execute(tx->{
                // A new setting locks the node only for this short settings transaction, never for delivery.
                if (jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",settings.nodeId()).isEmpty()) throw new IllegalArgumentException("节点不存在");
                admin(userId);
                AirportSyncState old=load(settings.nodeId(),true);
                if (settings.revision()!=(old==null?0:old.revision)) throw new IllegalArgumentException("设置已被其他管理员更新，请刷新");
                AirportSyncState state=new AirportSyncState();
                state.endpoint=endpoint;state.bindingId=binding;state.enabled=settings.enabled();state.revision=settings.revision()+1;
                state.sourceMode=settings.sourceMode();
                boolean same=old!=null && endpoint.equals(old.endpoint) && binding.equals(old.bindingId);
                state.encryptedToken=token.isEmpty() && same ? old.encryptedToken : token.isEmpty() ? "" : crypto(settings.nodeId(),endpoint,binding).encrypt(token);
                if (state.enabled && state.encryptedToken.isEmpty()) throw new IllegalArgumentException("启用或更换绑定时请提供独立绑定密钥");
                state.status=state.enabled?"waiting_source":"disabled";
                String destination=AirportSyncState.digest(endpoint+":"+binding);
                if (old==null) jdbc.update("INSERT INTO node_airport_sync (node_id,destination_key,enabled,payload) VALUES (?,?,?,?)",
                        settings.nodeId(),destination,state.enabled?1:0,JSON.toJSONString(state));
                else jdbc.update("UPDATE node_airport_sync SET destination_key=?,enabled=?,payload=? WHERE node_id=?",
                        destination,state.enabled?1:0,JSON.toJSONString(state),settings.nodeId());
                return view(settings.nodeId(),state);
            });
        } catch (DuplicateKeyException duplicate) { throw new IllegalArgumentException("该机场绑定已关联其他转发节点"); }
    }
    public void remove(Long userId,Long id,Long revision) {
        admin(userId);node(id);
        transactions.executeWithoutResult(tx->{
            AirportSyncState state=load(id,true);admin(userId);
            if (state!=null && !Objects.equals(revision,state.revision)) throw new IllegalArgumentException("设置已更新，请刷新");
            jdbc.update("DELETE FROM node_airport_sync WHERE node_id=?",id);
        });
    }
    private Map<String,Object> view(long id,AirportSyncState state) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("nodeId",id);out.put("enabled",state.enabled);out.put("endpoint",state.endpoint);out.put("bindingId",state.bindingId);
        out.put("hasToken",!state.encryptedToken.isEmpty());out.put("revision",state.revision);out.put("sourceMode",state.sourceMode);
        out.put("status",state.enabled && "aws-imds".equals(state.sourceMode) && state.reportedSample==null && "waiting_source".equals(state.status) ? "waiting_observer" : state.status);
        out.put("lastIp",state.lastIp);out.put("lastSuccessAt",state.lastSuccessAt);
        out.put("nextAttemptAt",state.nextAttemptAt);out.put("attempts",state.attempts);
        // Candidate is derived only from a currently authenticated connection, not browser input or DNS.
        var sample=observation(id,state,clock.millis());out.put("sourceIp",sample==null?"":sample.ip());
        return out;
    }
    protected AirportSyncState.Sample sample(long id,long now) { return WebSocketServer.airportSample(id,now); }
    private AirportSyncState.Sample observation(long id,AirportSyncState state,long now) {
        var value="aws-imds".equals(state.sourceMode)?state.reportedSample:sample(id,now);
        return value!=null && now>=value.observedAt() && now-value.observedAt()<=30_000 ? value : null;
    }
    public Map<String,Object> observationPolicy(long id) {
        var state=load(id,false);
        boolean selected=state!=null && state.enabled && "aws-imds".equals(state.sourceMode);
        return Map.of("installEnabled",selected,"enabled",selected && !state.blocked,
                "sourceMode",state==null?"off":state.sourceMode);
    }
    public boolean needsReporter(long id) {
        var state=load(id,false);
        return state!=null && state.enabled && "aws-imds".equals(state.sourceMode);
    }
    public record Report(String ip,String instanceId,long observedAt,String bootId) { }
    public boolean report(Node node,Report report) {
        if (report==null || !AirportAddressPolicy.publicV4(report.ip()) || report.instanceId()==null
                || !report.instanceId().matches("i-[a-f0-9]{8,32}") || report.bootId()==null
                || !report.bootId().matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}") || report.observedAt()<clock.millis()/1000-120
                || report.observedAt()>clock.millis()/1000+30) throw new IllegalArgumentException("Invalid observation");
        return Boolean.TRUE.equals(transactions.execute(tx->{
            var state=load(node.getId(),true);
            if (state==null || !state.enabled || state.blocked || !"aws-imds".equals(state.sourceMode)) return false;
            // Repeated HTTP requests do not count as a second sample. Server receipt time drives stability.
            if (report.observedAt()<=state.reportObservedAt) {
                if (report.observedAt()==state.reportObservedAt && state.reportedSample!=null
                        && report.ip().equals(state.reportedSample.ip())
                        && (report.instanceId()+":"+report.bootId()).equals(state.reportedSample.connection())) return true;
                throw new IllegalArgumentException("Stale observation");
            }
            state.reportObservedAt=report.observedAt();
            state.reportedSample=new AirportSyncState.Sample(report.ip(),clock.millis(),report.instanceId()+":"+report.bootId(),AirportSyncState.digest(node.getSecret()));
            jdbc.update("UPDATE node_airport_sync SET payload=? WHERE node_id=?",JSON.toJSONString(state),node.getId());
            return true;
        }));
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
    @Scheduled(initialDelay=5000,fixedDelay=5000) public void tick() {
        if (!running.compareAndSet(false,true)) return;
        try {
            List<Long> ids=jdbc.queryForList("SELECT node_id FROM node_airport_sync WHERE enabled=1 AND node_id>? ORDER BY node_id LIMIT 64",Long.class,cursor);
            if (ids.isEmpty()) { cursor=0;running.set(false);return; }
            cursor=ids.getLast();
            CompletableFuture<?>[] jobs=ids.stream().map(id->CompletableFuture.runAsync(()->{
                try { process(id); } catch (RuntimeException failure) { /* Durable state remains for the next tick; no secret-bearing errors. */ }
            },workers)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(jobs).whenComplete((r,e)->running.set(false));
        } catch (RuntimeException failure) { running.set(false); }
    }
    void process(long id) {
        transactions.executeWithoutResult(tx->{
            AirportSyncState state=load(id,true);
            if (state==null || !state.enabled || state.blocked) return;
            Node node=nodes.selectById(id);
            if (node==null) { jdbc.update("DELETE FROM node_airport_sync WHERE node_id=?",id);return; }
            long now=clock.millis();
            var observation=observation(id,state,now);
            User owner=node.getOwnerUserId()==null ? null : users.selectById(node.getOwnerUserId());
            if (node.getOwnerUserId()!=null && (owner==null || !Objects.equals(owner.getStatus(),1))) observation=null;
            if (observation!=null && (node.getSecret()==null || !AirportSyncState.digest(node.getSecret()).equals(observation.credentialDigest()))) observation=null;
            if (state.observe(observation,now)) {
                String result;
                try {
                    String token=crypto(id,state.endpoint,state.bindingId).decryptString(state.encryptedToken);
                    // Binding settings and delivery serialize on this row, independent of forwarding tables/threads.
                    result=transport.send(state.endpoint,state.bindingId,token,state.pendingIp,state.pendingAt/1000);
                } catch (RuntimeException unavailable) { result="blocked_credential"; }
                state.completed(result,clock.millis());
            }
            jdbc.update("UPDATE node_airport_sync SET payload=? WHERE node_id=?",JSON.toJSONString(state),id);
        });
    }
}

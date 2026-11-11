package com.admin.common.service;

import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.ForwardMapper;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.admin.entity.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.admin.common.utils.AESCrypto;
import jakarta.annotation.PreDestroy;
import java.net.IDN;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service @DependsOn("trafficPoolMigration")
public class TrafficPoolService {
    public static final String MANAGED_ID = "00000000-0000-4000-8000-000000000001";
    static final String MANAGED_NAME = "独立流量池";
    private final JdbcTemplate jdbc;private final NodeGroupService groups;private final NodeMapper nodes;
    private final TunnelMapper tunnels;private final ForwardMapper forwards;private final ForwardPortReservationService ports;
    private final TunnelIngressNodeResolver ingress;private final TrafficPoolDns dns;private final TrafficPoolObserver observer;
    private final TransactionTemplate tx;private final String key;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"traffic-pool");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();private String cursor="";
    Clock clock=Clock.systemUTC();long startedAt=System.currentTimeMillis();
    public TrafficPoolService(JdbcTemplate jdbc,NodeGroupService groups,NodeMapper nodes,TunnelMapper tunnels,ForwardMapper forwards,
            ForwardPortReservationService ports,TunnelIngressNodeResolver ingress,TrafficPoolDns dns,TrafficPoolObserver observer,
            PlatformTransactionManager manager,@Value("${jwt-secret}")String key) {
        this.jdbc=jdbc;this.groups=groups;this.nodes=nodes;this.tunnels=tunnels;this.forwards=forwards;this.ports=ports;
        this.ingress=ingress;this.dns=dns;this.observer=observer;tx=new TransactionTemplate(manager);this.key=key;
    }
    public record MemberInput(long nodeId,boolean enabled,long quotaBytes,Long usedBytes) { }
    public record Settings(String id,String name,long revision,String mode,String direction,long quotaBytes,int resetDay,
            String domain,String zoneId,String token,boolean dnsEnabled,String dnsAcknowledgement,int probePort,
            int failureThreshold,int recoveryThreshold,int ttl,boolean keepLastAddress,List<MemberInput> members,List<Long> tunnelIds) {
        @Override public String toString() {return "TrafficPoolSettings[redacted]";}
    }
    TrafficPoolState load(String id,boolean lock) {
        var rows=jdbc.query("SELECT payload FROM traffic_pool WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->r.getString(1),NodeGroupService.id(id));
        if(rows.isEmpty())throw new IllegalArgumentException("流量池不存在");
        return JSON.parseObject(rows.getFirst(),TrafficPoolState.class);
    }
    List<TrafficPoolState.Member> members(String id) {
        return jdbc.query("SELECT payload FROM traffic_pool_member WHERE pool_id=? ORDER BY node_id",(r,n)->JSON.parseObject(r.getString(1),TrafficPoolState.Member.class),id);
    }
    String dnsKey(TrafficPoolState p){return p.domain.isEmpty()?null:AirportSyncState.digest(p.domain);}
    void persist(TrafficPoolState p) {jdbc.update("UPDATE traffic_pool SET name=?,dns_key=?,payload=? WHERE id=?",p.name,dnsKey(p),JSON.toJSONString(p),p.id);}
    void member(TrafficPoolState.Member m) {jdbc.update("UPDATE traffic_pool_member SET payload=? WHERE node_id=?",JSON.toJSONString(m),m.nodeId);}
    AESCrypto crypto(TrafficPoolState p) {return crypto(p.id,p.zoneId,p.domain);}
    private AESCrypto crypto(String id,String zone,String domain) {return new AESCrypto(key+":traffic-pool:v1:"+id+":"+zone+":"+domain);}
    TrafficPoolState managedForUpdate() {
        var pool = new TrafficPoolState();
        pool.id = MANAGED_ID;
        pool.name = MANAGED_NAME;
        jdbc.update("INSERT INTO traffic_pool(id,name,dns_key,payload) VALUES(?,?,NULL,?) ON DUPLICATE KEY UPDATE id=id",
                pool.id,pool.name,JSON.toJSONString(pool));
        return load(MANAGED_ID,true);
    }
    static String domain(String value) {
        if(value==null || value.isBlank())return "";
        String result=IDN.toASCII(value.trim(),IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if(result.endsWith("."))result=result.substring(0,result.length()-1);
        if(result.length()>253 || !result.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+") || result.matches("[0-9.]+"))throw new IllegalArgumentException("请输入有效的入口域名");
        return result;
    }
    public List<Map<String,Object>> list(Long user) {
        groups.admin(user);
        return jdbc.query("SELECT payload FROM traffic_pool WHERE id<>? ORDER BY name",(r,n)->view(JSON.parseObject(r.getString(1),TrafficPoolState.class)), MANAGED_ID);
    }
    public Map<String,Object> get(Long user,String id) {groups.admin(user);return view(load(id,false));}
    Map<String,Object> view(TrafficPoolState p) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",p.id);result.put("name",p.name);result.put("revision",p.revision);result.put("mode",p.mode);result.put("direction",p.direction);
        result.put("quotaBytes",p.quotaBytes);result.put("resetDay",p.resetDay);result.put("domain",p.domain);result.put("zoneId",p.zoneId);
        result.put("hasToken",!p.encryptedToken.isEmpty());result.put("dnsEnabled",p.dnsEnabled);result.put("dnsStatus",p.dnsStatus);
        result.put("probePort",p.probePort);result.put("failureThreshold",p.failureThreshold);result.put("recoveryThreshold",p.recoveryThreshold);
        result.put("ttl",p.ttl);result.put("keepLastAddress",p.keepLastAddress);result.put("checkedAt",p.checkedAt);result.put("lastSyncedAt",p.lastSyncedAt);result.put("tunnelIds",p.tunnelIds);
        var rows=members(p.id);long used=0,total=0;var detail=new ArrayList<Map<String,Object>>();
        for(var m:rows) {
            used=Math.addExact(used,m.usedBytes);total=Math.addExact(total,m.quotaBytes);
            var node=nodes.selectById(m.nodeId);var d=new LinkedHashMap<String,Object>();
            d.put("nodeId",m.nodeId);d.put("name",node==null?"已删除节点":node.getName());d.put("enabled",m.enabled);
            d.put("serverIp",node==null?"":observer.addresses(node).stream().findFirst().orElse(""));
            d.put("quotaBytes",m.quotaBytes);d.put("usedBytes",m.usedBytes);d.put("status",m.status);d.put("lastReportAt",m.lastAt);
            d.put("addresses",m.health.entrySet().stream().map(e->Map.of("ip",e.getKey(),"eligible",e.getValue().eligible,"failures",e.getValue().failures,"successes",e.getValue().successes)).toList());
            detail.add(d);
        }
        result.put("members",detail);result.put("usedBytes",used);result.put("totalBytes","shared".equals(p.mode)?p.quotaBytes:total);
        result.put("remainingBytes","shared".equals(p.mode)?Math.max(0,p.quotaBytes-used):rows.stream().filter(m->m.quotaBytes>0).mapToLong(m->Math.max(0,m.quotaBytes-m.usedBytes)).sum());
        result.put("accounting","estimate");result.put("desiredAddresses",TrafficPoolDns.desired(rows));
        return result;
    }
    public Map<String,Object> save(Long user,Settings s) {
        groups.admin(user);String name=NodeGroupService.name(s.name()),domain=domain(s.domain());
        if(MANAGED_ID.equals(s.id()) || MANAGED_NAME.equals(name))throw new IllegalArgumentException("请在独立流量池页面设置");
        if(!Set.of("shared","individual").contains(s.mode()) || !Set.of("in","out","both").contains(s.direction()) || s.quotaBytes()<0 || s.quotaBytes()>9_000_000_000_000_000L
                || s.resetDay()<1 || s.resetDay()>31 || s.probePort()<1 || s.probePort()>65535 || s.failureThreshold()<2 || s.failureThreshold()>10
                || s.recoveryThreshold()<2 || s.recoveryThreshold()>10 || s.ttl()<60 || s.ttl()>86400 || s.revision()<0)throw new IllegalArgumentException("请检查额度、周期、端口与健康检查参数");
        if(s.members()==null || s.members().size()>64 || s.members().stream().map(MemberInput::nodeId).distinct().count()!=s.members().size())throw new IllegalArgumentException("每池最多 64 个不同节点");
        if(s.tunnelIds()==null || s.tunnelIds().size()>32 || s.tunnelIds().stream().distinct().count()!=s.tunnelIds().size())throw new IllegalArgumentException("每池最多绑定 32 个不同隧道");
        if(s.dnsEnabled() && (domain.isEmpty() || !domain.equals(s.dnsAcknowledgement())))throw new IllegalArgumentException("启用自动 DNS 前请明确确认此入口域名");
        if(!domain.isEmpty())TrafficPoolDns.path(s.zoneId());
        String token=s.token()==null?"":s.token();if(!token.isEmpty() && !token.matches("[A-Za-z0-9_-]{20,256}"))throw new IllegalArgumentException("DNS 令牌格式无效");
        return tx.execute(t->{
            groups.admin(user);boolean create=s.id()==null || s.id().isEmpty();
            if(create && jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool",Long.class)>=32)throw new IllegalArgumentException("最多创建 32 个流量池");
            var p=create?new TrafficPoolState():load(s.id(),true);int oldReset=p.resetDay;
            if(p.revision!=s.revision())throw new IllegalArgumentException("流量池已更新，请刷新后重试");
            if(create)p.id=UUID.randomUUID().toString();
            if(!create && (!p.domain.equals(domain) || !p.zoneId.equals(s.zoneId())) && !p.domain.isEmpty())
                throw new IllegalArgumentException("已有入口域名不可原地更换；请停用并清理托管记录后新建流量池");
            p.name=name;p.mode=s.mode();p.direction=s.direction();p.quotaBytes=s.quotaBytes();p.resetDay=s.resetDay();p.domain=domain;p.zoneId=s.zoneId()==null?"":s.zoneId();
            if(!token.isEmpty())p.encryptedToken=crypto(p).encrypt(token);
            if(s.dnsEnabled() && p.encryptedToken.isEmpty())throw new IllegalArgumentException("启用自动 DNS 需要令牌");
            p.dnsEnabled=s.dnsEnabled();p.probePort=s.probePort();p.failureThreshold=s.failureThreshold();p.recoveryThreshold=s.recoveryThreshold();
            p.ttl=s.ttl();p.keepLastAddress=s.keepLastAddress();p.revision++;p.dnsStatus=p.dnsEnabled?"waiting_checks":"disabled";
            if(create)jdbc.update("INSERT INTO traffic_pool(id,name,dns_key,payload) VALUES(?,?,?,?)",p.id,p.name,dnsKey(p),JSON.toJSONString(p));
            var old=members(p.id);var desired=new TreeSet<Long>();
            for(var input:s.members().stream().sorted(Comparator.comparingLong(MemberInput::nodeId)).toList()) {
                if(input.nodeId()<=0 || input.quotaBytes()<0 || input.quotaBytes()>9_000_000_000_000_000L || input.usedBytes()!=null && (input.usedBytes()<0 || input.usedBytes()>9_000_000_000_000_000L))throw new IllegalArgumentException("节点额度或已用值无效");
                if(jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",input.nodeId()).isEmpty())throw new IllegalArgumentException("节点不存在");
                var selectedNode=nodes.selectById(input.nodeId());
                if(!domain.isEmpty() && domain.equalsIgnoreCase(NodeAddressResolver.target(selectedNode)))throw new IllegalArgumentException("池入口域名不能与成员自身的地址或 DDNS 域名相同，避免多个程序争写和循环解析");
                if(input.enabled() && observer.addresses(selectedNode).isEmpty())throw new IllegalArgumentException("参与解析的池成员必须在节点管理填写公网服务器 IP");
                var ownership=jdbc.queryForList("SELECT pool_id FROM traffic_pool_member WHERE node_id=?",String.class,input.nodeId());
                if(!ownership.isEmpty() && !p.id.equals(ownership.getFirst()))throw new IllegalArgumentException("节点已属于其他流量池");
                var m=old.stream().filter(x->x.nodeId==input.nodeId()).findFirst().orElseGet(TrafficPoolState.Member::new);
                m.nodeId=input.nodeId();m.enabled=input.enabled();m.quotaBytes=input.quotaBytes();
                if(oldReset!=p.resetDay)m.cycle=TrafficPoolState.cycle(clock.millis(),p.resetDay);
                if(input.usedBytes()!=null) {m.usedBytes=input.usedBytes();m.cycle=TrafficPoolState.cycle(clock.millis(),p.resetDay);m.lastAt=0;event(p.id,"usage_calibration","管理员校准节点 "+m.nodeId+" 的已用字节数");}
                if(ownership.isEmpty())jdbc.update("INSERT INTO traffic_pool_member VALUES(?,?,?)",m.nodeId,p.id,JSON.toJSONString(m));else member(m);
                desired.add(m.nodeId);
            }
            for(var m:old)if(!desired.contains(m.nodeId))throw new IllegalArgumentException("请将暂不使用的成员设为维护模式，以保留本期用量和旧连接；不会自动删除节点");
            bind(p,s.tunnelIds(),desired);p.tunnelIds=new ArrayList<>(s.tunnelIds());groups.admin(user);persist(p);event(p.id,"settings_saved","管理员更新流量池设置");return view(p);
        });
    }
    void bind(TrafficPoolState p,List<Long> ids,Set<Long> desired) {
        if(!new HashSet<>(ids).containsAll(p.tunnelIds))throw new IllegalArgumentException("已绑定隧道保留现有关系；请维护模式退池，避免删掉运行规则");
        for(Long id:new TreeSet<>(ids)) {
            if(id==null || jdbc.queryForList("SELECT id FROM tunnel WHERE id=? FOR UPDATE",id).isEmpty())throw new IllegalArgumentException("隧道不存在");
            var tunnel=tunnels.selectById(id);
            if(tunnel.getOwnerUserId()!=null)throw new IllegalArgumentException("只能绑定管理员创建的隧道");
            if(desired.isEmpty() || Objects.equals(tunnel.getType(),2) && tunnel.getOutNodeId()!=null && desired.contains(tunnel.getOutNodeId()))throw new IllegalArgumentException("池不能为空，且不能包含隧道出口节点");
            var owner=jdbc.queryForList("SELECT pool_id FROM traffic_pool_tunnel WHERE tunnel_id=?",String.class,id);
            if(!owner.isEmpty() && !p.id.equals(owner.getFirst()))throw new IllegalArgumentException("隧道已绑定其他流量池");
            var existing=ingress.resolveNodeIds(tunnel);
            if(!desired.containsAll(existing))throw new IllegalArgumentException("请将隧道现有全部入口加入池，保留旧连接");
            if(owner.isEmpty())jdbc.update("INSERT INTO traffic_pool_tunnel VALUES(?,?)",id,p.id);
            // Linking the existing topology does not need to touch its port claims.
            if(existing.containsAll(desired))continue;
            long now=clock.millis();
            for(Long node:desired)if(jdbc.queryForList("SELECT id FROM tunnel_entry_node WHERE tunnel_id=? AND node_id=?",id,node).isEmpty())
                jdbc.update("INSERT INTO tunnel_entry_node(tunnel_id,node_id,created_time,status) VALUES(?,?,?,1)",id,node,now);
            for(var f:forwards.selectList(new QueryWrapper<Forward>().eq("tunnel_id",id))) {
                for(Long node:desired) {
                    var n=nodes.selectById(node);
                    if(f.getInPort()==null || n.getPortSta()==null || n.getPortEnd()==null || f.getInPort()<n.getPortSta() || f.getInPort()>n.getPortEnd())throw new IllegalArgumentException("池成员端口范围不兼容现有规则");
                }
                var lease=ports.reserve(f,tunnel);if(!lease.isSuccess())throw new IllegalArgumentException(lease.getMessage());
                // No commit(): retain every existing port claim and running service during admission.
            }
        }
    }
    void event(String id,String kind,String detail) {jdbc.update("INSERT INTO traffic_pool_event(pool_id,happened_at,kind,detail) VALUES(?,?,?,?)",id,clock.millis(),kind,detail);}
    public List<Map<String,Object>> events(Long user,String id) {groups.admin(user);load(id,false);return jdbc.queryForList("SELECT happened_at,kind,detail FROM traffic_pool_event WHERE pool_id=? ORDER BY id DESC LIMIT 100",id);}
    String owner(TrafficPoolState p) {return "lunaris-pool/"+p.id;}
    public Object preview(Long user,String id) {
        groups.admin(user);return tx.execute(t->{
            var p=load(id,true);if(p.domain.isEmpty() || p.encryptedToken.isEmpty())throw new IllegalArgumentException("请先保存域名、Zone ID 和令牌");
            var records=dns.list(p.zoneId,p.domain,crypto(p).decryptString(p.encryptedToken));
            var result=new LinkedHashMap<String,Object>();result.put("domain",p.domain);result.put("records",records);result.put("revision",p.revision);result.put("fingerprint",fingerprint(records));
            try{result.put("changes",dns.plan(records,owner(p),TrafficPoolDns.desired(members(id))));result.put("blockedReason","");}
            catch(IllegalStateException blocked){result.put("changes",List.of());result.put("blockedReason",blocked.getMessage());}
            result.put("canAdopt",canAdopt(p,records));return result;
        });
    }
    String fingerprint(List<TrafficPoolDns.Record> records){return AirportSyncState.digest(JSON.toJSONString(records.stream().sorted(Comparator.comparing(TrafficPoolDns.Record::id)).toList()));}
    boolean canAdopt(TrafficPoolState p,List<TrafficPoolDns.Record> records){
        if(records.isEmpty() || records.size()>512 || records.stream().anyMatch(r->"CNAME".equals(r.type())))return false;
        var ips=new HashSet<String>();for(var m:members(p.id)){var n=nodes.selectById(m.nodeId);if(n!=null)ips.addAll(observer.addresses(n));}
        var candidates=records.stream().filter(r->Set.of("A","AAAA").contains(r.type())).toList();
        return candidates.stream().anyMatch(r->!owner(p).equals(r.comment())) && candidates.stream().allMatch(r->!r.proxied() && ips.contains(TrafficPoolDns.normalize(r.content()))
                && (r.comment()==null || !r.comment().startsWith("lunaris-pool/") || owner(p).equals(r.comment())));
    }
    public void adopt(Long user,String id,long revision,String expectedFingerprint,String acknowledgement){
        groups.admin(user);tx.executeWithoutResult(t->{
            var p=load(id,true);groups.admin(user);
            if(p.revision!=revision || !p.domain.equals(acknowledgement) || p.domain.isEmpty())throw new IllegalArgumentException("请重新预览并确认入口域名");
            String token=crypto(p).decryptString(p.encryptedToken);var records=dns.list(p.zoneId,p.domain,token);
            if(!fingerprint(records).equals(expectedFingerprint) || !canAdopt(p,records))throw new IllegalArgumentException("记录已变化或存在不属于当前成员的 IP，不能接管");
            var candidate=records.stream().filter(r->Set.of("A","AAAA").contains(r.type()) && !owner(p).equals(r.comment())).findFirst().orElseThrow();
            dns.adopt(p.zoneId,candidate,p.domain,owner(p),token);
            event(id,"dns_adopted","管理员确认接管 DNS 记录 "+candidate.content());
        });
    }
    @PreDestroy public void close(){worker.shutdownNow();}
    @Scheduled(initialDelay=15000,fixedDelay=5000,scheduler="trafficPoolScheduler")
    public void tick() {
        if(!running.compareAndSet(false,true))return;
        try {
            var ids=jdbc.queryForList("SELECT id FROM traffic_pool WHERE id>? ORDER BY id",String.class,cursor);
            if(ids.isEmpty()){cursor="";ids=jdbc.queryForList("SELECT id FROM traffic_pool ORDER BY id",String.class);}
            for(String id:ids){
                if(!inFlight.add(id)){cursor=id;continue;}
                try{worker.execute(()->{try{process(id);}catch(RuntimeException ignored){/* No secret-bearing diagnostics. */}finally{inFlight.remove(id);}});cursor=id;}
                catch(RejectedExecutionException full){inFlight.remove(id);break;}
            }
        }finally{running.set(false);}
    }
    void process(String id) {
        tx.executeWithoutResult(t->{
            var p=load(id,true);long now=clock.millis();if(now-p.checkedAt<20000)return;var rows=members(id);
            boolean managed = MANAGED_ID.equals(id);
            if (managed) {
                p.probePort = p.tunnelIds.isEmpty() ? 0 : forwards.selectList(new QueryWrapper<Forward>()
                        .eq("tunnel_id",p.tunnelIds.getFirst()).eq("status",1)).stream()
                        .map(Forward::getInPort).filter(Objects::nonNull).min(Integer::compare).orElse(0);
            }
            var allIps=new TreeSet<String>();
            for(var m:rows){var n=nodes.selectById(m.nodeId);if(n!=null && m.enabled && Objects.equals(n.getStatus(),1))allIps.addAll(observer.addresses(n));}
            var probes=p.probePort>0?observer.probeAll(allIps,p.probePort):Map.<String,Boolean>of();
            for(var m:rows) {
                long observedAt=clock.millis();var sample=observer.metrics(m.nodeId,observedAt);m.account(sample,observedAt,p.resetDay,p.direction);
                var node=nodes.selectById(m.nodeId);
                if(node==null || !m.enabled) {
                    m.health.values().forEach(h->{h.eligible=false;h.successes=0;});m.status=node==null?"missing":"maintenance";member(m);continue;
                }
                if(now-m.lastProbeAt<20000)continue;
                var addresses=observer.addresses(node);var before=TrafficPoolDns.desired(List.of(m));
                boolean online=Objects.equals(node.getStatus(),1),reported=online && observer.reported(m.nodeId,observedAt);
                boolean ready=reported && !addresses.isEmpty() && (!managed || p.probePort>0) && rulesReady(p,m.nodeId);
                for(String ip:addresses)m.health.computeIfAbsent(ip,k->new TrafficPoolState.Health());
                m.health.entrySet().removeIf(e->!e.getValue().eligible && !addresses.contains(e.getKey()) && now-e.getValue().checkedAt>1800000);
                for(var h:m.health.entrySet()) {
                    boolean bound=addresses.contains(h.getKey());
                    // Unknown probes are not failures; known offline/stale reporting is a failure even without a probe result.
                    if(p.probePort>0 && bound && reported && !probes.containsKey(h.getKey()))continue;
                    boolean ok=bound && reported && (ready || h.getValue().eligible) && Boolean.TRUE.equals(probes.get(h.getKey()));
                    if(now-startedAt<120000 && !ok && h.getValue().eligible)continue;
                    h.getValue().observe(ok,now,p.failureThreshold,p.recoveryThreshold);
                }
                m.lastProbeAt=now;m.status=addresses.isEmpty()?"ip_missing":!online?"offline":!reported?"report_stale":m.health.values().stream().anyMatch(h->h.eligible)?"healthy":!ready?"waiting_rules":"confirming";
                member(m);if(!before.equals(TrafficPoolDns.desired(List.of(m))))event(id,"health_changed","节点 "+m.nodeId+(!online?" 离线，已撤下入口候选":!reported?" 停止上报，已撤下入口候选":" 的入口候选已变化"));
            }
            p.checkedAt=now;
            if(!p.dnsEnabled){p.dnsStatus="disabled";persist(p);return;}
            var desired=TrafficPoolDns.desired(rows);
            try {
                String token=crypto(p).decryptString(p.encryptedToken);var records=dns.list(p.zoneId,p.domain,token);
                var changes=dns.plan(records,owner(p),desired);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                for(var change:changes.subList(0,Math.min(16,changes.size()))) {
                    if(System.nanoTime()>deadline)break;
                    if("add".equals(change.get("action")))dns.create(p.zoneId,p.domain,change.get("ip"),p.ttl,owner(p),token);
                    else {var r=records.stream().filter(x->x.id().equals(change.get("recordId"))).findFirst().orElseThrow();dns.delete(p.zoneId,r,p.domain,owner(p),token);}
                    event(id,"dns_"+change.get("action"),change.get("ip"));
                }
                var after=dns.list(p.zoneId,p.domain,token);
                p.dnsStatus=dns.plan(after,owner(p),desired).isEmpty()?"synced":"syncing";
                if("synced".equals(p.dnsStatus)) {
                    p.lastSyncedAt=clock.millis();
                    if (p.previousDns != null) retirePreviousDns(p);
                }
            } catch(RuntimeException failed) {p.dnsStatus="dns_error";}
            persist(p);
        });
    }
    private void retirePreviousDns(TrafficPoolState pool) {
        var previous = pool.previousDns;
        String token = crypto(pool.id,previous.zoneId(),previous.domain()).decryptString(previous.encryptedToken());
        var records = dns.list(previous.zoneId(),previous.domain(),token).stream()
                .filter(record -> owner(pool).equals(record.comment()) && Set.of("A","AAAA").contains(record.type())).toList();
        if (records.isEmpty()) {
            pool.previousDns = null;
            return;
        }
        dns.delete(previous.zoneId(),records.getFirst(),previous.domain(),owner(pool),token);
    }
    boolean rulesReady(TrafficPoolState p,long node) {
        // No tunnel means explicitly selected probe-only pool; bound pools require a real post-sync Agent inventory.
        if(p.tunnelIds.isEmpty())return true;
        var names=TrafficPoolInventory.names(node,clock.millis());if(names==null)return false;
        for(Long tunnel:p.tunnelIds)for(var f:forwards.selectList(new QueryWrapper<Forward>().eq("tunnel_id",tunnel).eq("status",1)))
            if(names.stream().noneMatch(n->n.startsWith(f.getId()+"_") && n.endsWith("_tcp")) || names.stream().noneMatch(n->n.startsWith(f.getId()+"_") && n.endsWith("_udp")))return false;
        return true;
    }
}

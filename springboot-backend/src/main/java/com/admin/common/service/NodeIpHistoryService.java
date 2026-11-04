package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.admin.common.utils.WebSocketServer;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@DependsOn("nodeIpHistoryMigration")
public class NodeIpHistoryService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final NodeAddressResolver resolver;
    private final TransactionTemplate transactions;
    private Clock clock = Clock.systemUTC();
    private long cursor;
    private final AtomicBoolean observing = new AtomicBoolean();
    private final ExecutorService probes = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "node-ip-observer"); thread.setDaemon(true); return thread;
    });

    public NodeIpHistoryService(JdbcTemplate jdbc, NodeMapper nodes, UserMapper users,
                                NodeAddressResolver resolver, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.nodes=nodes; this.users=users; this.resolver=resolver;
        this.transactions=new TransactionTemplate(manager);
    }

    @PreDestroy public void close() { probes.shutdownNow(); }

    @Scheduled(initialDelay=15_000, fixedDelay=60_000)
    public void observeNodes() {
        if (!observing.compareAndSet(false,true)) return;
        try {
        List<Node> batch = nodes.selectList(new QueryWrapper<Node>().gt("id",cursor).orderByAsc("id").last("LIMIT 64"));
        if (batch.isEmpty()) {
            cursor=0;
            batch=nodes.selectList(new QueryWrapper<Node>().orderByAsc("id").last("LIMIT 64"));
        }
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Node node : batch) {
            cursor=node.getId();
            pending.add(CompletableFuture.runAsync(() -> {
                String target=NodeAddressResolver.target(node);
                var result=resolver.resolve(target);
                record(node.getId(),target,result,clock.millis());
            }, probes));
        }
        // Never block the shared scheduler: forwarding retries must continue even when DNS is slow.
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((unused,failure) -> {
            observing.set(false);
            if (failure!=null) log.warn("节点 IP 观测未完成，将在下一轮重试");
        });
        } catch (RuntimeException failure) {
            observing.set(false);
            log.warn("节点 IP 观测暂时无法调度，将在下一轮重试");
        }
    }

    void record(Long nodeId, String target, NodeIpObservation.Result result, long now) {
        record(nodeId,target,result,now,WebSocketServer.continuitySample(nodeId,now));
    }

    void record(Long nodeId, String target, NodeIpObservation.Result result, long now, NodeContinuityObservation.Sample sample) {
        transactions.executeWithoutResult(tx -> {
            if (jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",nodeId).isEmpty()) return;
            Node current=nodes.selectById(nodeId);
            if (current==null || !Objects.equals(target,NodeAddressResolver.target(current))) return;
            List<String> saved=jdbc.query("SELECT payload FROM node_ip_state WHERE node_id=?",(rs,n)->rs.getString(1),nodeId);
            var state=saved.isEmpty() ? new NodeIpObservation.State() : JSON.parseObject(saved.get(0),NodeIpObservation.State.class);
            if (now<=state.getCheckedAt()) return;
            if (state.getContinuity()==null) state.setContinuity(new NodeContinuityObservation.State());
            var continuityEvent=NodeContinuityObservation.accept(state.getContinuity(),sample,now);
            long previousResolvedAt=state.getResolvedAt();
            long previousCandidateSince=state.getCandidateSince();
            var event=NodeIpObservation.accept(state,target,result,now);
            if (state.getCandidateSince()>0 && state.getCandidateSince()!=previousCandidateSince)
                state.setIpEvidenceStart(previousResolvedAt);
            jdbc.update("INSERT INTO node_ip_state (node_id,payload,checked_at) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE payload=VALUES(payload),checked_at=VALUES(checked_at)",
                    nodeId,JSON.toJSONString(state),state.getCheckedAt());
            if (event!=null) {
                var evidence="CHANGE".equals(event.kind()) ? NodeContinuityObservation.classifyIp(state.getContinuity(),state.getIpEvidenceStart(),now) : null;
                jdbc.update("INSERT INTO node_ip_event (node_id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at,context_json) VALUES (?,?,?,?,?,?,?,?,?)",
                        nodeId,event.kind(),target,result.source(),JSON.toJSONString(event.previous()),
                        JSON.toJSONString(event.addresses()),event.observedAt(),event.confirmedAt(),evidence==null ? null : JSON.toJSONString(evidence));
            }
            if (continuityEvent!=null) {
                jdbc.update("INSERT INTO node_ip_event (node_id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at,context_json) VALUES (?,?,?,?,?,?,?,?,?)",
                        nodeId,continuityEvent.kind(),target,result.source(),"[]",JSON.toJSONString(state.getAddresses()),
                        continuityEvent.observedAt(),continuityEvent.confirmedAt(),JSON.toJSONString(continuityEvent.evidence()));
            }
        });
    }

    @Scheduled(initialDelay=300_000, fixedDelay=3_600_000)
    public void prune() {
        jdbc.update("DELETE FROM node_ip_event WHERE confirmed_at < ? LIMIT 5000",clock.millis()-90L*24*60*60*1000);
        jdbc.update("DELETE s FROM node_ip_state s LEFT JOIN node n ON n.id=s.node_id WHERE n.id IS NULL");
        jdbc.update("DELETE e FROM node_ip_event e LEFT JOIN node n ON n.id=e.node_id WHERE n.id IS NULL");
    }

    User activeUser(Long userId) {
        User user=userId==null ? null : users.selectById(userId);
        if (user==null || !Objects.equals(user.getStatus(),1) || !Objects.equals(user.getRoleId(),0))
            throw new SecurityException("仅管理员可查看 IP 记录");
        return user;
    }

    Node managedNode(User user, Long nodeId) {
        Node node=nodeId==null ? null : nodes.selectById(nodeId);
        if (node==null || !Objects.equals(user.getRoleId(),0)) {
            throw new SecurityException("仅管理员可查看节点 IP 记录");
        }
        return node;
    }

    private long dayStart(int daysAgo) { return LocalDate.now(clock.withZone(ZONE)).minusDays(daysAgo).atStartOfDay(ZONE).toInstant().toEpochMilli(); }

    public List<Map<String,Object>> summaries(Long userId) {
        activeUser(userId);
        List<Node> manageable=nodes.selectList(new QueryWrapper<Node>().orderByAsc("id"));
        if (manageable.isEmpty()) return List.of();
        Map<Long,NodeIpObservation.State> states=new HashMap<>();
        jdbc.query("SELECT s.node_id,s.payload FROM node_ip_state s INNER JOIN node n ON n.id=s.node_id",
                rs -> { states.put(rs.getLong(1),JSON.parseObject(rs.getString(2),NodeIpObservation.State.class)); });
        Map<Long,long[]> counts=new HashMap<>();
        jdbc.query("SELECT e.node_id,SUM(CASE WHEN e.confirmed_at>=? THEN 1 ELSE 0 END),COUNT(*) "
                        + "FROM node_ip_event e INNER JOIN node n ON n.id=e.node_id WHERE e.kind='CHANGE' AND e.confirmed_at>=? GROUP BY e.node_id",
                rs -> { counts.put(rs.getLong(1),new long[]{rs.getLong(2),rs.getLong(3)}); },dayStart(0),dayStart(6));
        List<Map<String,Object>> result=new ArrayList<>();
        for (Node node:manageable) result.add(summary(node,states.get(node.getId()),counts.getOrDefault(node.getId(),new long[2])));
        activeUser(userId);
        return result;
    }

    private Map<String,Object> summary(Node node, NodeIpObservation.State state, long[] counts) {
        boolean current=state!=null && Objects.equals(state.getTarget(),NodeAddressResolver.target(node));
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("nodeId",node.getId()); item.put("target",NodeAddressResolver.target(node));
        item.put("ddnsEnabled",Objects.equals(node.getDdnsEnabled(),1));
        item.put("source",current ? state.getSource() : "dns");
        item.put("status",!current ? "pending" : clock.millis()-state.getCheckedAt()>300_000 ? "stale" : state.getStatus());
        item.put("addresses",current ? state.getAddresses() : List.of());
        item.put("checkedAt",current ? state.getCheckedAt() : 0);
        item.put("resolvedAt",current ? state.getResolvedAt() : 0);
        item.put("startedAt",state==null ? 0 : state.getStartedAt());
        item.put("baselineAt",current ? state.getBaselineAt() : 0);
        item.put("lastChangeAt",state==null ? 0 : state.getLastChangeAt());
        item.put("todayChanges",counts[0]); item.put("weekChanges",counts[1]);
        item.put("timezone",ZONE.getId());
        return item;
    }

    public Map<String,Object> history(Long userId, Long nodeId, Long beforeId) {
        User user=activeUser(userId); Node node=managedNode(user,nodeId);
        if (beforeId!=null && beforeId<=0) throw new IllegalArgumentException("无效分页位置");
        List<String> stored=jdbc.query("SELECT payload FROM node_ip_state WHERE node_id=?",(rs,n)->rs.getString(1),nodeId);
        var state=stored.isEmpty() ? null : JSON.parseObject(stored.get(0),NodeIpObservation.State.class);
        List<Map<String,Object>> grouped=jdbc.queryForList("SELECT FLOOR((confirmed_at+28800000)/86400000) AS day_number,COUNT(*) AS total "
                + "FROM node_ip_event WHERE node_id=? AND kind='CHANGE' AND confirmed_at>=? GROUP BY day_number",nodeId,dayStart(6));
        Map<Long,Long> daily=new HashMap<>();
        for (var row:grouped) daily.put(((Number)row.get("day_number")).longValue(),((Number)row.get("total")).longValue());
        long today=LocalDate.now(clock.withZone(ZONE)).toEpochDay();
        List<Map<String,Object>> chart=new ArrayList<>();
        for (int i=6;i>=0;i--) chart.add(Map.of("date",LocalDate.ofEpochDay(today-i).toString(),"count",daily.getOrDefault(today-i,0L)));
        long[] counts={daily.getOrDefault(today,0L),daily.values().stream().mapToLong(Long::longValue).sum()};
        Object[] args=beforeId==null ? new Object[]{nodeId} : new Object[]{nodeId,beforeId};
        List<Map<String,Object>> events=jdbc.query("SELECT id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at,context_json FROM node_ip_event WHERE node_id=?"
                +(beforeId==null ? "" : " AND id<?")+" ORDER BY id DESC LIMIT 51",(rs,n)-> {
            Map<String,Object> e=new LinkedHashMap<>();
            e.put("id",rs.getLong("id")); e.put("kind",rs.getString("kind")); e.put("target",rs.getString("target")); e.put("source",rs.getString("source"));
            e.put("previousAddresses",JSON.parseArray(rs.getString("previous_addresses"),String.class));
            e.put("addresses",JSON.parseArray(rs.getString("addresses"),String.class));
            String context=rs.getString("context_json");
            e.put("evidence",context==null ? null : JSON.parseObject(context,NodeContinuityObservation.Evidence.class));
            e.put("observedAt",rs.getLong("observed_at")); e.put("confirmedAt",rs.getLong("confirmed_at")); return e;
        },args);
        boolean more=events.size()>50;
        if (more) events=new ArrayList<>(events.subList(0,50));
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("summary",summary(node,state,counts)); response.put("days",chart); response.put("events",events);
        response.put("nextBeforeId",more ? events.get(events.size()-1).get("id") : null);
        response.put("retentionDays",90);
        // Separate counts: an address change and a suspected replacement may be related, never sum them.
        Map<String,Long> changes=new LinkedHashMap<>();
        changes.put("ipOnlyLikely",0L); changes.put("machineSuspected",0L); changes.put("unknownIp",0L);
        jdbc.query("SELECT kind,JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.classification')) AS classification,COUNT(*) AS total "
                + "FROM node_ip_event WHERE node_id=? AND confirmed_at>=? AND kind IN ('CHANGE','MACHINE_SUSPECTED') GROUP BY kind,classification",
                rs -> {
                    String key="MACHINE_SUSPECTED".equals(rs.getString(1)) ? "machineSuspected"
                            : "IP_ONLY_LIKELY".equals(rs.getString(2)) ? "ipOnlyLikely"
                            : "MACHINE_SUSPECTED".equals(rs.getString(2)) ? "ipNearMachine" : "unknownIp";
                    changes.merge(key,rs.getLong(3),Long::sum);
                },nodeId,dayStart(0));
        response.put("todayClassification",changes);
        // Never return credentials, installation keys, or another tenant's infrastructure addresses.
        managedNode(activeUser(userId),nodeId);
        return response;
    }
}

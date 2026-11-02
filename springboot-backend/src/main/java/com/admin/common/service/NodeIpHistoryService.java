package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
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
        transactions.executeWithoutResult(tx -> {
            if (jdbc.queryForList("SELECT id FROM node WHERE id=? FOR UPDATE",nodeId).isEmpty()) return;
            Node current=nodes.selectById(nodeId);
            if (current==null || !Objects.equals(target,NodeAddressResolver.target(current))) return;
            List<String> saved=jdbc.query("SELECT payload FROM node_ip_state WHERE node_id=?",(rs,n)->rs.getString(1),nodeId);
            var state=saved.isEmpty() ? new NodeIpObservation.State() : JSON.parseObject(saved.get(0),NodeIpObservation.State.class);
            var event=NodeIpObservation.accept(state,target,result,now);
            jdbc.update("INSERT INTO node_ip_state (node_id,payload,checked_at) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE payload=VALUES(payload),checked_at=VALUES(checked_at)",
                    nodeId,JSON.toJSONString(state),state.getCheckedAt());
            if (event!=null) {
                jdbc.update("INSERT INTO node_ip_event (node_id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at) VALUES (?,?,?,?,?,?,?,?)",
                        nodeId,event.kind(),target,result.source(),JSON.toJSONString(event.previous()),
                        JSON.toJSONString(event.addresses()),event.observedAt(),event.confirmedAt());
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
        if (user==null || !Objects.equals(user.getStatus(),1)) throw new SecurityException("当前用户不可查看 IP 记录");
        return user;
    }

    Node managedNode(User user, Long nodeId) {
        Node node=nodeId==null ? null : nodes.selectById(nodeId);
        if (node==null || (!Objects.equals(user.getRoleId(),0) && !Objects.equals(node.getOwnerUserId(),user.getId()))) {
            throw new SecurityException("只能查看自己管理的节点 IP 记录");
        }
        return node;
    }

    private long dayStart(int daysAgo) { return LocalDate.now(clock.withZone(ZONE)).minusDays(daysAgo).atStartOfDay(ZONE).toInstant().toEpochMilli(); }

    public List<Map<String,Object>> summaries(Long userId) {
        User user=activeUser(userId);
        QueryWrapper<Node> scope=new QueryWrapper<>();
        if (!Objects.equals(user.getRoleId(),0)) scope.eq("owner_user_id",userId);
        List<Node> manageable=nodes.selectList(scope.orderByAsc("id"));
        if (manageable.isEmpty()) return List.of();
        boolean admin=Objects.equals(user.getRoleId(),0);
        String owner=admin ? "" : " AND n.owner_user_id=?";
        Object[] stateArgs=admin ? new Object[]{} : new Object[]{userId};
        Map<Long,NodeIpObservation.State> states=new HashMap<>();
        jdbc.query("SELECT s.node_id,s.payload FROM node_ip_state s INNER JOIN node n ON n.id=s.node_id WHERE 1=1"+owner,
                rs -> { states.put(rs.getLong(1),JSON.parseObject(rs.getString(2),NodeIpObservation.State.class)); },stateArgs);
        Map<Long,long[]> counts=new HashMap<>();
        Object[] countArgs=admin ? new Object[]{dayStart(0),dayStart(6)} : new Object[]{dayStart(0),dayStart(6),userId};
        jdbc.query("SELECT e.node_id,SUM(CASE WHEN e.confirmed_at>=? THEN 1 ELSE 0 END),COUNT(*) "
                        + "FROM node_ip_event e INNER JOIN node n ON n.id=e.node_id WHERE e.kind='CHANGE' AND e.confirmed_at>=?"+owner+" GROUP BY e.node_id",
                rs -> { counts.put(rs.getLong(1),new long[]{rs.getLong(2),rs.getLong(3)}); },countArgs);
        List<Map<String,Object>> result=new ArrayList<>();
        for (Node node:manageable) result.add(summary(node,states.get(node.getId()),counts.getOrDefault(node.getId(),new long[2])));
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
        List<Map<String,Object>> events=jdbc.query("SELECT id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at FROM node_ip_event WHERE node_id=?"
                +(beforeId==null ? "" : " AND id<?")+" ORDER BY id DESC LIMIT 51",(rs,n)-> {
            Map<String,Object> e=new LinkedHashMap<>();
            e.put("id",rs.getLong("id")); e.put("kind",rs.getString("kind")); e.put("target",rs.getString("target")); e.put("source",rs.getString("source"));
            e.put("previousAddresses",JSON.parseArray(rs.getString("previous_addresses"),String.class));
            e.put("addresses",JSON.parseArray(rs.getString("addresses"),String.class));
            e.put("observedAt",rs.getLong("observed_at")); e.put("confirmedAt",rs.getLong("confirmed_at")); return e;
        },args);
        boolean more=events.size()>50;
        if (more) events=new ArrayList<>(events.subList(0,50));
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("summary",summary(node,state,counts)); response.put("days",chart); response.put("events",events);
        response.put("nextBeforeId",more ? events.get(events.size()-1).get("id") : null);
        response.put("retentionDays",90);
        // Never return credentials, installation keys, or another tenant's infrastructure addresses.
        managedNode(activeUser(userId),nodeId);
        return response;
    }
}

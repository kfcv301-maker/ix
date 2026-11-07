package com.admin.common.service;

import com.admin.common.utils.AESCrypto;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Operator-configured airport peers. No endpoint or credential is supplied by a node. */
@Service
@DependsOn("airportSyncMigration")
public class AirportConnectionService {
    private final JdbcTemplate jdbc;
    private final UserMapper users;
    private final AirportSyncTransport transport;
    private final TransactionTemplate tx;
    private final String secret;
    private final boolean sharedLocks;
    public AirportConnectionService(JdbcTemplate jdbc,UserMapper users,AirportSyncTransport transport,
            PlatformTransactionManager manager,@Value("${jwt-secret}") String secret) {
        this.jdbc=jdbc;this.users=users;this.transport=transport;this.tx=new TransactionTemplate(manager);this.secret=secret;
        String database=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        sharedLocks="MySQL".equalsIgnoreCase(database) || "MariaDB".equalsIgnoreCase(database);
    }
    public static class Connection {
        public String id="",name="",origin="",connectionId="",encryptedToken="",status="unchecked";
        public boolean enabled;
        public long revision,checkedAt;
        public List<Binding> bindings=new ArrayList<>();
    }
    public record Binding(String bindingId,long relayNodeId,String name) { }
    public record Settings(String id,String name,String origin,String connectionId,String token,boolean enabled,Long revision) {
        @Override public String toString() { return "AirportConnectionSettings[redacted]"; }
    }
    private void admin(Long userId) {
        var u=userId==null?null:users.selectById(userId);
        if (u==null || !Objects.equals(u.getRoleId(),0) || !Objects.equals(u.getStatus(),1)) throw new SecurityException("仅管理员可管理机场连接");
    }
    public String panelId() { return jdbc.queryForObject("SELECT panel_id FROM airport_panel_identity WHERE id=1",String.class); }
    static String uuid(String value) {
        if (value==null || !value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IllegalArgumentException("请输入有效的连接或绑定 ID");
        return value;
    }
    Connection load(String id,boolean lock) {
        return loadWithClause(id,lock?" FOR UPDATE":"");
    }
    Connection delivery(String id) {
        // Multiple nodes may deliver concurrently. Settings writes still wait for existing deliveries.
        // H2 test databases do not implement InnoDB shared row locks; use their exclusive fallback.
        return loadWithClause(id,sharedLocks?" LOCK IN SHARE MODE":" FOR UPDATE");
    }
    private Connection loadWithClause(String id,String clause) {
        if (id==null || id.isEmpty()) return null;
        var rows=jdbc.query("SELECT payload FROM airport_connection WHERE id=?"+clause,(r,n)->r.getString(1),uuid(id));
        return rows.isEmpty()?null:JSON.parseObject(rows.getFirst(),Connection.class);
    }
    private AESCrypto crypto(Connection c) { return new AESCrypto(secret+":airport-connection:v2:"+c.id+":"+c.origin+":"+c.connectionId); }
    private void persist(Connection c) { jdbc.update("UPDATE airport_connection SET payload=? WHERE id=?",JSON.toJSONString(c),c.id); }
    private Map<String,Object> view(Connection c) {
        return Map.of("id",c.id,"name",c.name,"origin",c.origin,"connectionId",c.connectionId,
                "hasToken",!c.encryptedToken.isEmpty(),"enabled",c.enabled,"revision",c.revision,
                "status",c.status,"checkedAt",c.checkedAt,"bindings",c.bindings);
    }
    public Map<String,Object> list(Long userId) {
        admin(userId);
        var rows=jdbc.query("SELECT payload FROM airport_connection ORDER BY id",(r,n)->view(JSON.parseObject(r.getString(1),Connection.class)));
        return Map.of("panelId",panelId(),"connections",rows);
    }
    public Map<String,Object> save(Long userId,Settings s) {
        admin(userId);
        String origin=AirportAddressPolicy.origin(s.origin());
        String name=s.name()==null?"":s.name().trim(),token=s.token()==null?"":s.token();
        String remoteValue=s.connectionId();
        if (token.startsWith("ns2.")) {
            String[] parts=token.split("\\.",-1);
            if (parts.length!=3) throw new IllegalArgumentException("配对 Token 格式错误");
            remoteValue=parts[1];token=parts[2];
        }
        final String remote=uuid(remoteValue),credential=token;
        if (name.isEmpty() || name.length()>80 || s.revision()==null || s.revision()<0) throw new IllegalArgumentException("请填写连接名称并刷新版本");
        if (!token.isEmpty() && !token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("请输入机场生成的 64 位配对密钥");
        return tx.execute(status->{
            Connection old=load(s.id(),true);
            if (s.id()!=null && !s.id().isEmpty() && old==null) throw new IllegalArgumentException("连接不存在");
            if (s.revision()!=(old==null?0:old.revision)) throw new IllegalArgumentException("连接已更新，请重新读取");
            admin(userId);
            Connection c=new Connection();c.id=old==null?UUID.randomUUID().toString():old.id;c.name=name;c.origin=origin;c.connectionId=remote;c.enabled=s.enabled();c.revision=s.revision()+1;
            boolean same=old!=null && origin.equals(old.origin) && remote.equals(old.connectionId);
            c.encryptedToken=credential.isEmpty() && same?old.encryptedToken:credential.isEmpty()?"":crypto(c).encrypt(credential);
            if (c.enabled && c.encryptedToken.isEmpty()) throw new IllegalArgumentException("启用连接需要独立配对密钥");
            // An edit invalidates the remote permission snapshot; test again before enabling node reports.
            c.status=c.enabled?"unchecked":"disabled";
            if (old==null) jdbc.update("INSERT INTO airport_connection (id,payload) VALUES (?,?)",c.id,JSON.toJSONString(c));
            else persist(c);
            return view(c);
        });
    }
    public Map<String,Object> check(Long userId,String id,long revision) {
        admin(userId);
        return tx.execute(status->{
            Connection c=load(id,true);admin(userId);
            if (c==null || !c.enabled || c.revision!=revision) throw new IllegalArgumentException("连接未启用或已修改，请刷新");
            c.bindings=new ArrayList<>();c.checkedAt=System.currentTimeMillis();
            try {
                String path="/api/v1/node-address-sync/connection/check";
                var result=transport.exchange(c.origin+path,path,crypto(c).decryptString(c.encryptedToken),JSONObject.of("connection_id",c.connectionId,"relay_panel_id",panelId()));
                c.status=result.status();
                if ("ok".equals(c.status)) {
                    JSONObject data=verified(result.body(),c);
                    if (!(data.get("mappings") instanceof com.alibaba.fastjson2.JSONArray bindings)) throw new IllegalArgumentException();
                    if (!"authorized".equals(data.get("status")) || integer(data.get("server_time"))<=0 || bindings==null || bindings.size()>100) throw new IllegalArgumentException();
                    Set<String> ids=new HashSet<>();
                    for (int i=0;i<bindings.size();i++) {
                        if (!(bindings.get(i) instanceof JSONObject b)) throw new IllegalArgumentException();
                        String bid=uuid(string(b.get("binding_id")));long nid=integer(b.get("relay_node_id"));
                        String label=string(b.get("label"));
                        if (nid<=0 || nid>Integer.MAX_VALUE || !ids.add(bid) || label.length()>160 || !(b.get("enabled") instanceof Boolean)) throw new IllegalArgumentException();
                        if (Boolean.TRUE.equals(b.get("enabled"))) c.bindings.add(new Binding(bid,nid,label));
                    }
                    c.status="connected";
                }
            } catch (RuntimeException invalid) { c.status="retry_receipt";c.bindings.clear(); }
            c.revision++;
            persist(c);return view(c);
        });
    }
    private JSONObject verified(String body,Connection c) {
        JSONObject json=JSONObject.parseObject(body);
        if (!(json.get("data") instanceof JSONObject data)) throw new IllegalArgumentException();
        if (json.containsKey("code") && integer(json.get("code"))!=0) throw new IllegalArgumentException();
        if (data==null || integer(data.get("protocol_version"))!=2 || !c.connectionId.equals(data.get("connection_id")) || !panelId().equals(data.get("relay_panel_id"))) throw new IllegalArgumentException();
        return data;
    }
    private static long integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number)value).longValue();
    }
    private static String string(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException();
        return (String)value;
    }
    boolean allowed(Connection c,long nodeId,String bindingId) {
        return c!=null && c.enabled && "connected".equals(c.status) && c.bindings.stream().anyMatch(b->b.relayNodeId()==nodeId && b.bindingId().equals(bindingId));
    }
    String send(Connection c,long nodeId,String bindingId,String ip,long at) {
        if (!allowed(c,nodeId,bindingId)) return "blocked_connection";
        try {
            String path="/api/v1/node-address-sync/connection/report";
            var result=transport.exchange(c.origin+path,path,crypto(c).decryptString(c.encryptedToken),JSONObject.of(
                    "connection_id",c.connectionId,"relay_panel_id",panelId(),"relay_node_id",nodeId,"binding_id",bindingId,"ip",ip,"observed_at",at));
            if (!"ok".equals(result.status())) return result.status();
            JSONObject data=verified(result.body(),c);
            return nodeId==integer(data.get("relay_node_id")) && integer(data.get("updated"))>=0 && bindingId.equals(data.get("binding_id")) && ip.equals(data.get("ip"))
                    && Set.of("updated","unchanged","duplicate").contains(string(data.get("status")))?"synced":"retry_receipt";
        } catch (RuntimeException invalid) { return "retry_receipt"; }
    }
}

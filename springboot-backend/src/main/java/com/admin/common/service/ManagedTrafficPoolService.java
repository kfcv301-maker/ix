package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.VpsHost;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.NodeService;
import com.admin.service.VpsSshService;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.admin.common.service.TrafficPoolService.MANAGED_ID;
import static com.admin.common.service.TrafficPoolService.MANAGED_NAME;

@Service
public class ManagedTrafficPoolService {
    private static final long MAX_TRAFFIC_BYTES = 9_000_000_000_000_000L;
    private static final long INTERRUPTED_INSTALL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final int MAX_BATCH = 16;
    private static final String HOST_COLUMNS = "node_id,server_ip,ssh_port,fingerprint,install_status,message,updated_at";

    private final JdbcTemplate jdbc;
    private final NodeGroupService users;
    private final TrafficPoolService pools;
    private final TrafficPoolDns dns;
    private final NodeMapper nodes;
    private final TunnelMapper tunnels;
    private final NodeService nodeService;
    private final VpsSshService ssh;
    private final TransactionTemplate tx;
    private final Set<Long> activeInstalls = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor installs = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_BATCH), Thread.ofPlatform().name("pool-install-",0).daemon().factory());

    public ManagedTrafficPoolService(JdbcTemplate jdbc, NodeGroupService users, TrafficPoolService pools,
                                    TrafficPoolDns dns, NodeMapper nodes, TunnelMapper tunnels,
                                    NodeService nodeService, VpsSshService ssh, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.users = users;
        this.pools = pools;
        this.dns = dns;
        this.nodes = nodes;
        this.tunnels = tunnels;
        this.nodeService = nodeService;
        this.ssh = ssh;
        this.tx = new TransactionTemplate(manager);
    }

    public record Settings(long revision, String domain, String token) {
        @Override public String toString() { return "PoolSettings[redacted]"; }
    }
    public record PoolSettings(String id, long revision, String name, String domain, String zoneId,
                               String token, Long tunnelId) {
        @Override public String toString() { return "PoolSettings[redacted]"; }
    }
    public record Enrollment(String ip, int port, String password, String panelOrigin, long usedBytes, long quotaBytes,
                             String username) {
        public Enrollment(String ip, int port, String password, String panelOrigin, long usedBytes, long quotaBytes) {
            this(ip,port,password,panelOrigin,usedBytes,quotaBytes,null);
        }
        @Override public String toString() { return "PoolEnrollment[redacted]"; }
    }
    public record Batch(String id, List<Enrollment> servers) {
        @Override public String toString() { return "PoolBatch[redacted]"; }
    }
    public record EnrollmentResult(int index, boolean accepted, String message) { }
    public record AddressChange(String id, long nodeId, String ip, String username, String password) {
        @Override public String toString() { return "PoolAddressChange[redacted]"; }
    }
    public record Server(long id, String ip, Integer port, String status, String message, long usedBytes, long quotaBytes,
                         String name, Boolean published) { }
    public record View(long revision, String domain, boolean hasToken, String dnsStatus, Long tunnelId,
                       List<Server> servers) { }
    public record PoolSummary(String id, String name, long revision, String domain, String zoneId, boolean hasToken,
                              String dnsStatus, Long tunnelId, int probePort) { }
    public record PoolDetail(PoolSummary pool, List<Server> servers) { }
    private record Host(long id, String ip, int port, String fingerprint, String state, String message, long updatedAt) {
        String currentState(boolean active) {
            return "installing".equals(state) && !active && System.currentTimeMillis()-updatedAt>INTERRUPTED_INSTALL_MS
                    ? "failed" : state;
        }
    }

    public List<PoolSummary> configured(Long user) {
        users.admin(user);
        return jdbc.query("SELECT payload FROM traffic_pool ORDER BY name",
                (r,i)->summary(JSON.parseObject(r.getString(1),TrafficPoolState.class)));
    }

    public PoolDetail detail(Long user, String id) {
        users.admin(user);
        return detail(pools.load(id,false));
    }

    private PoolSummary summary(TrafficPoolState pool) {
        return new PoolSummary(pool.id,pool.name,pool.revision,pool.domain,pool.zoneId,
                !pool.encryptedToken.isEmpty(),pool.dnsStatus,
                pool.tunnelIds.isEmpty()?null:pool.tunnelIds.getFirst(),pool.probePort);
    }

    private PoolDetail detail(TrafficPoolState pool) {
        var members = pools.members(pool.id);
        List<String> published = "synced".equals(pool.dnsStatus)
                ? pool.publishedAddresses == null ? TrafficPoolDns.desired(members) : pool.publishedAddresses
                : null;
        var servers = new ArrayList<Server>();
        for (var member : members) {
            Host host = hostById(member.nodeId);
            Node node = nodes.selectById(member.nodeId);
            var bound = pools.boundIps(node);
            String ip = bound.isEmpty() ? host == null ? "" : host.ip : bound.getFirst();
            String status = member.status;
            String message = null;
            if (host != null) {
                String state = host.currentState(activeInstalls.contains(host.id));
                if (!"installed".equals(state)) status = state;
                message = "failed".equals(state) && host.message == null ? "安装中断，请重试" : host.message;
            }
            if (host == null && !member.enabled) status = "maintenance";
            Boolean inDns = published == null ? null : published.stream()
                    .anyMatch(address -> bound.contains(address) || member.health.containsKey(address));
            servers.add(new Server(member.nodeId,ip,host==null?null:host.port,status,message,
                    member.usedBytes,member.quotaBytes,host!=null || node==null?"":node.getName(),inDns));
        }
        return new PoolDetail(summary(pool),servers);
    }

    public View get(Long user) {
        users.admin(user);
        var pool = readPool();
        if (pool == null) return new View(0,"",false,"disabled",null,List.of());
        var detail = detail(pool);
        return new View(pool.revision,pool.domain,!pool.encryptedToken.isEmpty(),pool.dnsStatus,
                detail.pool.tunnelId,detail.servers);
    }

    public View save(Long user, Settings settings) {
        users.admin(user);
        configure(user,new PoolSettings(MANAGED_ID,settings.revision,MANAGED_NAME,settings.domain,"",
                settings.token,null),true);
        return get(user);
    }

    public PoolSummary configure(Long user, PoolSettings settings) {
        return configure(user,settings,false);
    }

    private PoolSummary configure(Long user, PoolSettings settings, boolean legacy) {
        users.admin(user);
        String domain = TrafficPoolService.domain(settings.domain);
        if (domain.isEmpty()) throw new IllegalArgumentException("请填写入口域名");
        String name = NodeGroupService.name(settings.name);
        boolean create = settings.id == null || settings.id.isBlank();
        String id = create ? UUID.randomUUID().toString() : NodeGroupService.id(settings.id);
        var before = create ? null : legacy ? readPool() : pools.load(id,false);
        if ((before==null?0:before.revision)!=settings.revision)
            throw new IllegalStateException("设置已变化，请刷新后重试");
        if (!legacy && create && MANAGED_NAME.equals(name))
            throw new IllegalArgumentException("该名称已保留，请使用其他流量池名称");
        boolean newToken = settings.token != null && !settings.token.isBlank();
        String token = newToken ? settings.token.trim() : before==null || before.encryptedToken.isEmpty()
                ? "" : pools.crypto(before).decryptString(before.encryptedToken);
        if (!token.matches("[A-Za-z0-9_-]{20,256}")) throw new IllegalArgumentException("请填写有效的 Cloudflare API Token");
        String requestedZone = settings.zoneId == null ? "" : settings.zoneId.trim().toLowerCase(java.util.Locale.ROOT);
        boolean sameDomain = before != null && domain.equals(before.domain);
        String zone = sameDomain && !newToken && !before.zoneId.isEmpty() ? before.zoneId : dns.findZone(domain,token);
        if (!requestedZone.isEmpty() && !requestedZone.equals(zone))
            throw new IllegalArgumentException("Zone ID 与入口域名所属站点不一致");
        dns.plan(dns.list(zone,domain,token),"lunaris-pool/"+id,List.of());

        try {
            return tx.execute(status -> {
                users.admin(user);
                var pool = create ? new TrafficPoolState() : legacy ? pools.managedForUpdate() : pools.load(id,true);
                if (pool.revision != settings.revision) throw new IllegalStateException("设置已变化，请刷新后重试");
                if (create && jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool",Long.class)>=32)
                    throw new IllegalArgumentException("最多创建 32 个流量池");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE ddns_enabled=1 AND LOWER(ddns_record_name)=?",Long.class,domain)>0)
                    throw new IllegalArgumentException("该域名已由单个节点管理");
                Long existingTunnel = pool.tunnelIds.isEmpty()?null:pool.tunnelIds.getFirst();
                if (pool.tunnelIds.size()>1 || settings.tunnelId!=null && existingTunnel!=null && !existingTunnel.equals(settings.tunnelId))
                    throw new IllegalArgumentException("流量池只能绑定一条隧道");
                boolean changed = !pool.domain.equals(domain) || !pool.zoneId.equals(zone);
                if (changed && pool.previousDns != null) throw new IllegalStateException("上一次域名切换仍在同步");
                if (changed && !pool.domain.isEmpty() && pool.dnsEnabled) {
                    pool.previousDns = new TrafficPoolState.PreviousDns(pool.domain,pool.zoneId,pool.encryptedToken);
                }
                if (changed) {
                    pool.publishedAddresses=null;
                    pool.lastSyncedAt=0;
                }
                pool.id=id;
                pool.name=name;
                pool.domain=domain;
                pool.zoneId=zone;
                pool.encryptedToken=pools.crypto(pool).encrypt(token);
                pool.dnsEnabled=true;
                pool.dnsStatus="waiting_checks";
                pool.revision++;
                if (create) {
                    pool.automaticProbe=true;
                    pool.probePort=0;
                    if (settings.tunnelId==null && tunnels.selectCount(new QueryWrapper<Tunnel>().eq("name",name))>0)
                        throw new IllegalArgumentException("已有同名隧道，请改用其他流量池名称或选择现有隧道");
                    jdbc.update("INSERT INTO traffic_pool(id,name,dns_key,payload) VALUES(?,?,?,?)",
                            id,name,pools.dnsKey(pool),JSON.toJSONString(pool));
                }
                if (existingTunnel==null && settings.tunnelId!=null) {
                    var tunnel=lockCompatibleTunnel(settings.tunnelId,domain);
                    pools.bind(pool,List.of(tunnel.getId()),pools.tunnelEntries(tunnel));
                    pool.tunnelIds.add(tunnel.getId());
                } else if (changed && existingTunnel!=null) {
                    jdbc.update("UPDATE tunnel SET in_ip=?, updated_time=? WHERE id=?",domain,System.currentTimeMillis(),existingTunnel);
                }
                pools.persist(pool);
                pools.event(id,"settings_saved","管理员更新流量池设置");
                return summary(pool);
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalArgumentException("流量池名称、域名或隧道已被使用");
        }
    }

    private Tunnel lockCompatibleTunnel(Long id, String domain) {
        if (jdbc.queryForList("SELECT id FROM tunnel WHERE id=? FOR UPDATE",id).isEmpty())
            throw new IllegalArgumentException("隧道不存在");
        var tunnel=tunnels.selectById(id);
        if (tunnel==null || tunnel.getOwnerUserId()!=null || !Objects.equals(tunnel.getType(),1)
                || !Objects.equals(tunnel.getStatus(),1) || !domain.equalsIgnoreCase(tunnel.getInIp()))
            throw new IllegalArgumentException("请选择入口域名相同的管理员直连隧道");
        return tunnel;
    }

    public void enroll(Long user, Enrollment input) {
        users.admin(user);
        validate(input);
        enroll(MANAGED_ID,input,true);
    }

    public List<EnrollmentResult> enrollBatch(Long user, Batch batch) {
        users.admin(user);
        if (batch.servers==null || batch.servers.isEmpty() || batch.servers.size()>MAX_BATCH)
            throw new IllegalArgumentException("一次可添加 1–16 台服务器");
        var pool=pools.load(batch.id,false);
        requireConfigured(pool);
        var results=new ArrayList<EnrollmentResult>();
        var ips=new HashSet<String>();
        for (int i=0;i<batch.servers.size();i++) {
            var input=batch.servers.get(i);
            try {
                String ip=validate(input);
                if (!ips.add(ip)) throw new IllegalArgumentException("本次提交中 IP 重复");
                enroll(pool.id,input,false);
                results.add(new EnrollmentResult(i,true,null));
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                results.add(new EnrollmentResult(i,false,rejected.getMessage()));
            } catch (DataIntegrityViolationException conflict) {
                results.add(new EnrollmentResult(i,false,"该服务器已存在，请刷新后重试"));
            }
        }
        return results;
    }

    public void changeAddress(Long user, AddressChange input) {
        users.admin(user);
        var pool=pools.load(input.id,false);
        requireConfigured(pool);
        var previous=hostById(input.nodeId);
        if (previous==null || !"installed".equals(previous.state) || previous.fingerprint==null
                || !poolOwnsHost(pool.id,input.nodeId))
            throw new IllegalArgumentException("请选择本流量池中已安装的服务器");
        String ip=publicIp(input.ip);
        if (previous.ip.equals(ip)) throw new IllegalArgumentException("请填写新的公网 IP");
        String username=validLogin(input.username,input.password);
        requireUnusedAddress(ip);
        var host=new VpsHost();
        host.setHost(ip);
        host.setSshPort(previous.port);
        host.setSshUsername(username);
        host.setSshFingerprint(previous.fingerprint);
        host.setOrigin("POOL");
        var check=ssh.check(host,input.password);
        if (!check.isOnline() || !previous.fingerprint.equals(check.getFingerprint()))
            throw new IllegalArgumentException(check.isFingerprintChanged()?"新 IP 的 SSH 主机身份与原服务器不同":"无法用原服务器身份登录新 IP");
        if (pool.probePort>0 && !pools.probeBoundIp(ip,pool.probePort))
            throw new IllegalArgumentException("新 IP 的转发端口探测失败");
        try {
            tx.executeWithoutResult(status -> {
                users.admin(user);
                var currentPool=pools.load(pool.id,true);
                if (currentPool.revision!=pool.revision || currentPool.probePort!=pool.probePort
                        || !poolOwnsHost(pool.id,input.nodeId))
                    throw new IllegalStateException("流量池设置已变化，请重试");
                var current=jdbc.query("SELECT "+HOST_COLUMNS+" FROM traffic_pool_host WHERE node_id=? FOR UPDATE",
                        this::host,input.nodeId);
                if (!current.equals(List.of(previous)))
                    throw new IllegalStateException("服务器状态已变化，请重试");
                requireUnusedAddress(ip);
                long now=System.currentTimeMillis();
                if (jdbc.update("UPDATE node SET server_ip=?,ip=?,updated_time=? WHERE id=? AND server_ip=? AND ip=?",
                        ip,ip,now,input.nodeId,previous.ip,previous.ip)!=1)
                    throw new IllegalStateException("服务器地址已变化，请刷新后重试");
                jdbc.update("UPDATE traffic_pool_host SET server_ip=?,updated_at=? WHERE node_id=?",ip,now,input.nodeId);
                pools.event(pool.id,"address_changed","管理员更新流量池服务器 IP");
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalArgumentException("该 IP 已是面板中的节点");
        }
    }

    private boolean poolOwnsHost(String poolId,long nodeId) {
        return jdbc.queryForList("SELECT pool_id FROM traffic_pool_member WHERE node_id=?",String.class,nodeId)
                .equals(List.of(poolId));
    }

    private void requireUnusedAddress(String ip) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE server_ip=? OR ip=?",Long.class,ip,ip)>0)
            throw new IllegalArgumentException("该 IP 已是面板中的节点，请填写新服务器");
    }

    private static void requireConfigured(TrafficPoolState pool) {
        if (pool.domain.isEmpty() || !pool.dnsEnabled || pool.encryptedToken.isEmpty())
            throw new IllegalStateException("请先配置流量池的入口域名和 DNS");
        if (pool.tunnelIds.size()>1) throw new IllegalStateException("添加服务器需要流量池只绑定一条隧道");
    }

    private static String validLogin(String username,String password) {
        String login=username==null || username.isBlank()?"root":username.trim();
        if (!login.matches("[A-Za-z_][A-Za-z0-9_.-]{0,31}")) throw new IllegalArgumentException("SSH 用户名格式不正确");
        if (password==null || password.isBlank() || password.length()>512) throw new IllegalArgumentException("请填写 SSH 密码");
        return login;
    }

    private static String validate(Enrollment input) {
        if (input==null) throw new IllegalArgumentException("请填写服务器信息");
        String ip=publicIp(input.ip);
        if (input.port<1 || input.port>65535) throw new IllegalArgumentException("SSH 端口须为 1–65535");
        validLogin(input.username,input.password);
        if (input.usedBytes<0 || input.quotaBytes<0 || input.usedBytes>MAX_TRAFFIC_BYTES || input.quotaBytes>MAX_TRAFFIC_BYTES)
            throw new IllegalArgumentException("流量须为有效的非负数");
        validateOrigin(input.panelOrigin);
        return ip;
    }

    private void enroll(String poolId, Enrollment input, boolean legacy) {
        String ip=publicIp(input.ip);
        long id=Objects.requireNonNull(tx.execute(status -> {
            var pool=legacy?pools.managedForUpdate():pools.load(poolId,true);
            if (!legacy) requireConfigured(pool);
            var existing=jdbc.query("SELECT "+HOST_COLUMNS+" FROM traffic_pool_host WHERE server_ip=? FOR UPDATE",this::host,ip);
            if (!existing.isEmpty()) {
                Host host=existing.getFirst();
                if (!poolOwnsHost(pool.id,host.id) || !"failed".equals(host.currentState(activeInstalls.contains(host.id))))
                    throw new IllegalArgumentException("该服务器已在流量池中");
                jdbc.update("UPDATE traffic_pool_host SET ssh_port=?,install_status='installing',message=NULL,updated_at=? WHERE node_id=?",
                        input.port,System.currentTimeMillis(),host.id);
                return host.id;
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool_member WHERE pool_id=?",Long.class,pool.id)>=64)
                throw new IllegalArgumentException("该池最多挂载 64 台服务器");
            requireUnusedAddress(ip);
            Node node=new Node();
            node.setName("池节点 "+ip);
            node.setSecret(UUID.randomUUID().toString().replace("-",""));
            node.setIp(ip);
            node.setServerIp(ip);
            node.setPortSta(1024);
            node.setPortEnd(65535);
            node.setStatus(0);
            node.setDdnsEnabled(0);
            node.setCreatedTime(System.currentTimeMillis());
            node.setUpdatedTime(node.getCreatedTime());
            nodes.insert(node);
            jdbc.update("INSERT INTO traffic_pool_host(node_id,server_ip,ssh_port,install_status,updated_at) VALUES(?,?,?,'installing',?)",
                    node.getId(),ip,input.port,System.currentTimeMillis());
            var member=new TrafficPoolState.Member();
            member.nodeId=node.getId();
            member.enabled=false;
            member.usedBytes=input.usedBytes;
            member.quotaBytes=input.quotaBytes;
            member.cycle=TrafficPoolState.cycle(System.currentTimeMillis(),pool.resetDay);
            jdbc.update("INSERT INTO traffic_pool_member(node_id,pool_id,payload) VALUES(?,?,?)",
                    member.nodeId,pool.id,JSON.toJSONString(member));
            return node.getId();
        }));
        activeInstalls.add(id);
        try {
            installs.execute(() -> {
                try { install(poolId,id,input); }
                finally { activeInstalls.remove(id); }
            });
        } catch (RejectedExecutionException busy) {
            activeInstalls.remove(id);
            failed(id,"安装任务较多，请稍后重试");
            throw new IllegalStateException("安装任务较多，请稍后重试");
        }
    }

    private void install(String poolId, long id, Enrollment input) {
        try {
            Host entry=Objects.requireNonNull(hostById(id));
            Node node=nodes.selectById(id);
            VpsHost host=new VpsHost();
            host.setHost(entry.ip);
            host.setSshPort(entry.port);
            host.setSshUsername(validLogin(input.username,input.password));
            host.setOrigin("POOL");
            host.setSshFingerprint(entry.fingerprint);
            var check=ssh.check(host,input.password);
            if (!check.isOnline() || check.getFingerprint()==null || !check.getFingerprint().matches("SHA256:[A-Za-z0-9+/]{43}")) {
                failed(id,check.isFingerprintChanged()?"SSH 主机指纹已变化":"SSH 登录失败，请检查 IP、端口、用户名和密码");
                return;
            }
            host.setSshFingerprint(check.getFingerprint());
            jdbc.update("UPDATE traffic_pool_host SET fingerprint=? WHERE node_id=?",check.getFingerprint(),id);
            String installer=nodeService.poolAgentInstaller(node,input.panelOrigin);
            int result=ssh.runPoolAgentScript(host,input.password,guardedInstaller(node,input.panelOrigin,installer),480);
            if (result!=0) {
                failed(id,switch(result) {
                    case 42 -> "服务器已有其他节点，安装已停止";
                    case 44 -> "此 SSH 用户需要免密码 sudo 权限";
                    default -> "安装未完成，请检查服务器网络后重试";
                });
                return;
            }
            attach(poolId,node);
            try { ssh.runPoolAgentScript(host,input.password,reportInventory(input.panelOrigin),25); }
            catch (Exception ignored) { /* The Agent also reports inventory on its normal schedule. */ }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            failed(id,"加入隧道未完成，请检查入口端口是否可用");
        } catch (Exception failure) {
            failed(id,"安装或加入隧道未完成，请重试");
        }
    }

    private void attach(String poolId, Node node) {
        tx.executeWithoutResult(status -> {
            var pool=pools.load(poolId,true);
            Tunnel tunnel=pool.tunnelIds.isEmpty()?createTunnel(pool,node):tunnels.selectById(pool.tunnelIds.getFirst());
            if (tunnel==null || pool.tunnelIds.size()>1 || !Objects.equals(tunnel.getType(),1))
                throw new IllegalArgumentException("流量池需要一条直连隧道");
            var member=pools.members(poolId).stream().filter(m->m.nodeId==node.getId()).findFirst().orElseThrow();
            var entries=new TreeSet<>(pools.tunnelEntries(tunnel));
            entries.add(node.getId());
            pools.bind(pool,List.of(tunnel.getId()),entries);
            member.enabled=true;
            member.status="waiting_rules";
            pools.member(member);
            pool.tunnelIds=new ArrayList<>(List.of(tunnel.getId()));
            pools.persist(pool);
            jdbc.update("UPDATE traffic_pool_host SET install_status='installed',message=NULL,updated_at=? WHERE node_id=?",
                    System.currentTimeMillis(),node.getId());
        });
    }

    private Tunnel createTunnel(TrafficPoolState pool, Node node) {
        if (tunnels.selectCount(new QueryWrapper<Tunnel>().eq("name",pool.name))>0)
            throw new IllegalArgumentException("已有同名隧道");
        var tunnel=new Tunnel();
        tunnel.setName(pool.name);
        tunnel.setType(1);
        tunnel.setFlow(2);
        tunnel.setTrafficRatio(BigDecimal.ONE);
        tunnel.setInNodeId(node.getId());
        tunnel.setOutNodeId(node.getId());
        tunnel.setInIp(pool.domain.isEmpty()?node.getServerIp():pool.domain);
        tunnel.setOutIp(node.getServerIp());
        tunnel.setProtocol("tls");
        tunnel.setTcpListenAddr("::");
        tunnel.setUdpListenAddr("::");
        tunnel.setStatus(1);
        tunnel.setCreatedTime(System.currentTimeMillis());
        tunnel.setUpdatedTime(tunnel.getCreatedTime());
        tunnels.insert(tunnel);
        jdbc.update("INSERT INTO tunnel_entry_node(tunnel_id,node_id,created_time,status) VALUES(?,?,?,1)",
                tunnel.getId(),node.getId(),tunnel.getCreatedTime());
        return tunnel;
    }

    static String publicIp(String value) {
        var address = AirportAddressPolicy.literal(value==null?null:value.trim());
        if (address==null || !AirportAddressPolicy.publicAddress(address)) throw new IllegalArgumentException("请填写公网服务器 IP");
        return address.getHostAddress();
    }

    private static void validateOrigin(String origin) {
        try {
            var uri = java.net.URI.create(origin);
            if ("https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null
                    && uri.getRawQuery()==null && uri.getRawFragment()==null && "".equals(uri.getRawPath())
                    && origin.matches("https://[A-Za-z0-9.\\[\\]:-]+")) return;
        } catch (IllegalArgumentException | NullPointerException ignored) { }
        throw new IllegalArgumentException("请通过 HTTPS 面板安装流量池节点");
    }

    static String guardedInstaller(Node node, String origin, String installer) {
        return """
                set -eu
                [ "$(id -u)" -eq 0 ] || exit 42
                exec 9>/run/lunaris-pool-agent-install.lock
                flock -n 9 || exit 43
                config=/etc/flux-panel-agent/config.json
                if [ -e "$config" ] || [ -L "$config" ]; then
                  [ -f "$config" ] && [ ! -L "$config" ] || exit 42
                  python3 - "$config" '%s' '%s' <<'POOL_IDENTITY' || exit 42
                import hashlib, hmac, json, sys
                with open(sys.argv[1], encoding='utf-8') as f:
                    config = json.load(f)
                digest = hashlib.sha256(config.get('secret', '').encode()).hexdigest()
                if not hmac.compare_digest(digest, sys.argv[2]) or config.get('addr', '').rstrip('/') != sys.argv[3]:
                    sys.exit(42)
                POOL_IDENTITY
                  if systemctl is-active --quiet flux-panel-agent; then exit 0; fi
                elif systemctl cat flux-panel-agent.service >/dev/null 2>&1 || systemctl is-active --quiet gost.service; then
                  exit 42
                fi
                %s
                """.formatted(AirportSyncState.digest(node.getSecret()),origin,installer);
    }

    private static String reportInventory(String origin) {
        return """
                python3 - <<'POOL_INVENTORY'
                import json, pathlib, urllib.request
                root = pathlib.Path('/etc/flux-panel-agent')
                identity = json.loads((root / 'config.json').read_bytes())
                assert identity['addr'].rstrip('/') == '%s'
                config = json.loads((root / 'gost.json').read_bytes())
                for field in ('services', 'chains', 'limiters'):
                    if config.get(field) is None: config[field] = []
                request = urllib.request.Request('%s/flow/config', data=json.dumps(config).encode(),
                    headers={'Authorization': 'Bearer ' + identity['secret'], 'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=15) as response:
                    assert response.read(64).strip() == b'ok'
                POOL_INVENTORY
                """.formatted(origin,origin);
    }

    private TrafficPoolState readPool() {
        var states = jdbc.queryForList("SELECT payload FROM traffic_pool WHERE id=?",String.class,MANAGED_ID);
        return states.isEmpty()?null:JSON.parseObject(states.getFirst(),TrafficPoolState.class);
    }

    private Host hostById(long id) {
        var hosts=jdbc.query("SELECT "+HOST_COLUMNS+" FROM traffic_pool_host WHERE node_id=?",this::host,id);
        return hosts.isEmpty()?null:hosts.getFirst();
    }

    private Host host(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new Host(result.getLong(1),result.getString(2),result.getInt(3),result.getString(4),
                result.getString(5),result.getString(6),result.getLong(7));
    }

    private void failed(long id, String message) {
        jdbc.update("UPDATE traffic_pool_host SET install_status='failed',message=?,updated_at=? WHERE node_id=? AND install_status='installing'",
                message,System.currentTimeMillis(),id);
    }

    @PreDestroy public void shutdown() { installs.shutdownNow(); }
}

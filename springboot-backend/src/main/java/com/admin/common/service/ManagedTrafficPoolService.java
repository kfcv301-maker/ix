package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.VpsHost;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.NodeService;
import com.admin.service.VpsSshService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PreDestroy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.admin.common.service.TrafficPoolService.MANAGED_ID;
import static com.admin.common.service.TrafficPoolService.MANAGED_NAME;

@Service
public class ManagedTrafficPoolService {
    private static final long MAX_TRAFFIC_BYTES = 9_000_000_000_000_000L;
    private static final long EXPIRED_INSTALL_MS = TimeUnit.MINUTES.toMillis(30);
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
    private final ThreadPoolExecutor installs = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().name("pool-install-",0).daemon().factory());

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
    public record Enrollment(String ip, int port, String password, String panelOrigin, long usedBytes, long quotaBytes) {
        @Override public String toString() { return "PoolEnrollment[redacted]"; }
    }
    public record Server(long id, String ip, int port, String status, String message, long usedBytes, long quotaBytes) { }
    public record View(long revision, String domain, boolean hasToken, String dnsStatus, Long tunnelId,
                       List<Server> servers) { }
    private record Host(long id, String ip, int port, String fingerprint, String state, String message, long updatedAt) {
        String currentState() {
            return state.equals("installing") && System.currentTimeMillis()-updatedAt>EXPIRED_INSTALL_MS ? "failed" : state;
        }
    }

    public View get(Long user) {
        users.admin(user);
        var pool = readPool();
        if (pool == null) return new View(0,"",false,"disabled",null,List.of());
        var servers = pools.members(MANAGED_ID).stream().map(member -> {
            Host host = hostById(member.nodeId);
            String state = host.currentState();
            String status = "installed".equals(state) ? member.status : state;
            String message = "failed".equals(state) && host.message == null ? "安装未完成，可重试" : host.message;
            return new Server(host.id, host.ip, host.port, status, message, member.usedBytes, member.quotaBytes);
        }).toList();
        return new View(pool.revision,pool.domain,!pool.encryptedToken.isEmpty(),pool.dnsStatus,
                pool.tunnelIds.isEmpty()?null:pool.tunnelIds.getFirst(),servers);
    }

    public View save(Long user, Settings settings) {
        users.admin(user);
        String domain = TrafficPoolService.domain(settings.domain);
        if (domain.isEmpty()) throw new IllegalArgumentException("请填写入口域名");
        var before = readPool();
        if ((before == null ? 0 : before.revision) != settings.revision) throw new IllegalStateException("设置已变化，请刷新后重试");
        String token = settings.token == null ? "" : settings.token.trim();
        boolean sameDomain = before != null && domain.equals(before.domain);
        if (token.isEmpty() && before != null && !before.encryptedToken.isEmpty())
            token = pools.crypto(before).decryptString(before.encryptedToken);
        if (!token.matches("[A-Za-z0-9_-]{20,256}")) throw new IllegalArgumentException("请填写有效的 Cloudflare API Token");
        String zone = sameDomain && !before.zoneId.isEmpty() && (settings.token == null || settings.token.isBlank())
                ? before.zoneId : dns.findZone(domain,token);
        dns.plan(dns.list(zone,domain,token),"lunaris-pool/"+MANAGED_ID,List.of());
        String checkedToken = token;
        try {
            tx.executeWithoutResult(status -> {
                users.admin(user);
                var pool = pools.managedForUpdate();
                if (pool.revision != settings.revision) throw new IllegalStateException("设置已变化，请刷新后重试");
                if (!domain.equals(pool.domain) && pool.previousDns != null) throw new IllegalStateException("上一次域名切换仍在同步");
                if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE ddns_enabled=1 AND LOWER(ddns_record_name)=?",Long.class,domain)>0)
                    throw new IllegalArgumentException("该域名已由单个节点管理");
                if (!pool.domain.isEmpty() && (!domain.equals(pool.domain) || !zone.equals(pool.zoneId))) {
                    if (pool.previousDns != null) throw new IllegalStateException("上一次域名切换仍在同步");
                    pool.previousDns = new TrafficPoolState.PreviousDns(pool.domain,pool.zoneId,pool.encryptedToken);
                }
                pool.domain = domain;
                pool.zoneId = zone;
                pool.encryptedToken = pools.crypto(pool).encrypt(checkedToken);
                pool.dnsEnabled = true;
                pool.dnsStatus = "waiting_checks";
                pool.revision++;
                if (!pool.tunnelIds.isEmpty())
                    jdbc.update("UPDATE tunnel SET in_ip=?, updated_time=? WHERE id=?",domain,System.currentTimeMillis(),pool.tunnelIds.getFirst());
                pools.persist(pool);
            });
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalArgumentException("该域名已绑定其他流量池");
        }
        return get(user);
    }

    public void enroll(Long user, Enrollment input) {
        users.admin(user);
        String ip = publicIp(input.ip);
        if (input.port < 1 || input.port > 65535) throw new IllegalArgumentException("SSH 端口须为 1–65535");
        if (input.password == null || input.password.isBlank() || input.password.length()>512)
            throw new IllegalArgumentException("请填写 SSH 密码");
        if (input.usedBytes<0 || input.quotaBytes<0 || input.usedBytes>MAX_TRAFFIC_BYTES || input.quotaBytes>MAX_TRAFFIC_BYTES)
            throw new IllegalArgumentException("流量须为有效的非负数");
        validateOrigin(input.panelOrigin);

        long id = Objects.requireNonNull(tx.execute(status -> {
            users.admin(user);
            var pool = pools.managedForUpdate();
            var existing = jdbc.query("SELECT "+HOST_COLUMNS+" FROM traffic_pool_host WHERE server_ip=? FOR UPDATE",
                    (r,i)->new Host(r.getLong(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7)),ip);
            if (!existing.isEmpty()) {
                Host host = existing.getFirst();
                if (!"failed".equals(host.currentState())) throw new IllegalArgumentException("该服务器已在流量池中");
                jdbc.update("UPDATE traffic_pool_host SET ssh_port=?,install_status='installing',message=NULL,updated_at=? WHERE node_id=?",
                        input.port,System.currentTimeMillis(),host.id);
                return host.id;
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM traffic_pool_host",Long.class)>=64)
                throw new IllegalArgumentException("流量池已满");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE server_ip=? OR ip=?",Long.class,ip,ip)>0)
                throw new IllegalArgumentException("该 IP 已是面板中的节点，请填写新服务器");
            Node node = new Node();
            node.setName("池节点 "+ip);
            node.setSecret(UUID.randomUUID().toString().replace("-",""));
            node.setIp(ip);
            node.setServerIp(ip);
            node.setPortSta(10000);
            node.setPortEnd(65535);
            node.setStatus(0);
            node.setDdnsEnabled(0);
            node.setCreatedTime(System.currentTimeMillis());
            node.setUpdatedTime(node.getCreatedTime());
            nodes.insert(node);
            jdbc.update("INSERT INTO traffic_pool_host(node_id,server_ip,ssh_port,install_status,updated_at) VALUES(?,?,?,'installing',?)",
                    node.getId(),ip,input.port,System.currentTimeMillis());
            var member = new TrafficPoolState.Member();
            member.nodeId = node.getId();
            member.enabled = false;
            member.usedBytes = input.usedBytes;
            member.quotaBytes = input.quotaBytes;
            member.cycle = TrafficPoolState.cycle(System.currentTimeMillis(),pool.resetDay);
            jdbc.update("INSERT INTO traffic_pool_member(node_id,pool_id,payload) VALUES(?,?,?)",
                    member.nodeId,MANAGED_ID,JSON.toJSONString(member));
            return node.getId();
        }));
        try {
            installs.execute(() -> install(id,input.password,input.panelOrigin));
        } catch (RejectedExecutionException busy) {
            failed(id,"安装任务较多，请稍后重试");
            throw new IllegalStateException("安装任务较多，请稍后重试");
        }
    }

    private void install(long id, String password, String origin) {
        try {
            Host entry = hostById(id);
            Node node = nodes.selectById(id);
            VpsHost host = new VpsHost();
            host.setHost(entry.ip);
            host.setSshPort(entry.port);
            host.setSshUsername("root");
            host.setOrigin("POOL");
            host.setSshFingerprint(entry.fingerprint);
            var check = ssh.check(host,password);
            if (!check.isOnline() || check.getFingerprint() == null || !check.getFingerprint().matches("SHA256:[A-Za-z0-9+/]{43}")) {
                failed(id,check.isFingerprintChanged()?"SSH 主机指纹已变化":"SSH 登录失败，请检查 IP、端口和 root 密码");
                return;
            }
            host.setSshFingerprint(check.getFingerprint());
            jdbc.update("UPDATE traffic_pool_host SET fingerprint=? WHERE node_id=?",check.getFingerprint(),id);
            String installer = nodeService.poolAgentInstaller(node,origin);
            int result = ssh.runPoolAgentScript(host,password,guardedInstaller(node,origin,installer),480);
            if (result != 0) {
                failed(id,result==42?"服务器已有其他节点，安装已停止":"安装未完成，请检查服务器网络后重试");
                return;
            }
            attach(node);
            try { ssh.runPoolAgentScript(host,password,reportInventory(origin),25); }
            catch (Exception ignored) { /* The Agent also reports inventory on its normal schedule. */ }
        } catch (Exception failure) {
            failed(id,"安装或加入隧道未完成，请重试");
        }
    }

    private void attach(Node node) {
        tx.executeWithoutResult(status -> {
            var pool = pools.managedForUpdate();
            if (pool.tunnelIds.isEmpty()) {
                var tunnel = new Tunnel();
                tunnel.setName(MANAGED_NAME);
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
                pool.tunnelIds.add(tunnel.getId());
                jdbc.update("INSERT INTO tunnel_entry_node(tunnel_id,node_id,created_time,status) VALUES(?,?,?,1)",
                        tunnel.getId(),node.getId(),tunnel.getCreatedTime());
            }
            var rows = pools.members(MANAGED_ID);
            var member = rows.stream().filter(m -> m.nodeId==node.getId()).findFirst().orElseThrow();
            member.enabled = true;
            member.status = "waiting_rules";
            var members = new java.util.TreeSet<Long>();
            rows.stream().filter(m -> m.enabled).forEach(m -> members.add(m.nodeId));
            pools.bind(pool,pool.tunnelIds,members);
            pools.member(member);
            pools.persist(pool);
            jdbc.update("UPDATE traffic_pool_host SET install_status='installed',message=NULL,updated_at=? WHERE node_id=?",
                    System.currentTimeMillis(),node.getId());
        });
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
        return jdbc.queryForObject("SELECT "+HOST_COLUMNS+" FROM traffic_pool_host WHERE node_id=?",
                (r,i)->new Host(r.getLong(1),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7)),id);
    }

    private void failed(long id, String message) {
        jdbc.update("UPDATE traffic_pool_host SET install_status='failed',message=?,updated_at=? WHERE node_id=? AND install_status='installing'",
                message,System.currentTimeMillis(),id);
    }

    @PreDestroy public void shutdown() { installs.shutdownNow(); }
}

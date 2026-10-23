package com.admin.common.service;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Node-only configuration boundary. Never return this payload from browser APIs. */
@Service
public class NodeAgentConfigService {
    @Resource private NodeMapper nodeMapper;
    @Resource private UserMapper userMapper;
    @Value("${jwt-secret}") private String jwtSecret;
    @Value("${agent.install.release:1.4.16}") private String agentInstallRelease;

    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor sync = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "node-runtime-config");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** A separate installation capability, stable across login/password/release/config changes. */
    public static String installKey(Node node) {
        if (node == null || node.getId() == null || StrUtil.isBlank(node.getSecret())) {
            throw new IllegalArgumentException("Node installation identity unavailable");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(node.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                    ("node-install:v2:" + node.getId()).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Installation identity unavailable");
        }
    }

    public JSONObject installationConfig(Long nodeId, String capability) {
        if (nodeId == null || capability == null || !capability.matches("[A-Za-z0-9_-]{43}")) return null;
        Node node = nodeMapper.selectById(nodeId);
        if (!authorizedNode(node) || !MessageDigest.isEqual(installKey(node).getBytes(StandardCharsets.US_ASCII),
                capability.getBytes(StandardCharsets.US_ASCII))) return null;
        JSONObject result = runtimeConfig(node);
        String release = agentInstallRelease == null ? "" : agentInstallRelease.trim();
        if (!release.matches("[0-9]+(?:\\.[0-9]+){1,3}")) throw new IllegalStateException("Invalid Agent release");
        result.put("nodeId", node.getId());
        result.put("secret", node.getSecret());
        result.put("agentRelease", release);
        return result;
    }

    public JSONObject authenticatedRuntimeConfig(String secret) {
        if (StrUtil.isBlank(secret) || secret.length() > 256 || secret.contains("\n") || secret.contains("\r")) return null;
        Node node = nodeMapper.selectOne(new QueryWrapper<Node>().eq("secret", secret).last("LIMIT 1"));
        // MySQL collations may be case-insensitive; secrets never are.
        if (!authorizedNode(node) || !MessageDigest.isEqual(node.getSecret().getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) return null;
        return runtimeConfig(node);
    }

    private boolean authorizedNode(Node node) {
        if (node == null || StrUtil.isBlank(node.getSecret())) return false;
        if (node.getOwnerUserId() == null) return true;
        User owner = userMapper.selectById(node.getOwnerUserId());
        return owner != null && Objects.equals(owner.getStatus(), 1);
    }

    public JSONObject runtimeConfig(Node node) {
        JSONObject result = new JSONObject(new java.util.LinkedHashMap<>());
        result.put("schemaVersion", 1);
        result.put("tcpTuningProfile", profile(node.getTcpTuningProfile(), "balanced"));
        boolean auto = Integer.valueOf(1).equals(node.getTcpTuningAutoEnabled());
        result.put("tcpTuningAutoEnabled", auto);
        result.put("tcpTuningProfileMin", auto ? profile(node.getTcpTuningProfileMin(), "tiny") : "");
        result.put("tcpTuningProfileMax", auto ? profile(node.getTcpTuningProfileMax(), "balanced") : "");
        JSONObject ddns = new JSONObject(new java.util.LinkedHashMap<>());
        boolean enabled = Integer.valueOf(1).equals(node.getDdnsEnabled());
        ddns.put("enabled", enabled);
        ddns.put("provider", "cloudflare");
        ddns.put("intervalSeconds", 60);
        String token = "";
        String record = "";
        if (enabled) {
            record = node.getDdnsRecordName();
            if (record == null || !record.matches("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")) {
                throw new IllegalStateException("Invalid saved DDNS configuration");
            }
            try {
                token = new AESCrypto(jwtSecret + ":node-ddns:v1").decryptString(node.getDdnsToken());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Cannot read saved DDNS configuration");
            }
            if (StrUtil.isBlank(token) || token.contains("\r") || token.contains("\n")) {
                throw new IllegalStateException("Invalid saved DDNS configuration");
            }
        }
        ddns.put("apiToken", token);
        ddns.put("recordName", record);
        result.put("ddns", ddns);
        try {
            result.put("revision", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(result.toJSONString().getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Runtime configuration unavailable");
        }
        return result;
    }

    private String profile(String value, String fallback) {
        String result = StrUtil.isBlank(value) ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("tiny", "small", "balanced", "standard").contains(result)) {
            throw new IllegalStateException("Invalid saved TCP configuration");
        }
        return result;
    }

    /** Best-effort immediate delivery; authenticated Agent polling repairs offline/lost updates. */
    public void requestSync(Long nodeId) {
        if (nodeId == null || !queued.add(nodeId)) return;
        try {
            sync.execute(() -> {
                try {
                    Node node = nodeMapper.selectById(nodeId);
                    if (!authorizedNode(node) || !Integer.valueOf(1).equals(node.getStatus())) return;
                    GostDto capabilities = WebSocketServer.send_msg(nodeId, new JSONObject(), "NodeRuntimeConfigCapabilities");
                    if (capabilities == null || !"OK".equals(capabilities.getMsg()) || capabilities.getData() == null) return;
                    JSONObject data = JSONObject.from(capabilities.getData());
                    if (data.getIntValue("nodeRuntimeConfig") < 1) return;
                    // Re-read after the capability round trip so concurrent edits converge on the latest state.
                    Node latest = nodeMapper.selectById(nodeId);
                    if (authorizedNode(latest)) WebSocketServer.send_msg(nodeId, runtimeConfig(latest), "SetNodeRuntimeConfig");
                } catch (RuntimeException ignored) {
                    // Do not log configuration or provider credentials; the periodic pull retries safely.
                } finally {
                    queued.remove(nodeId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            queued.remove(nodeId);
        }
    }

    @PreDestroy public void close() { sync.shutdownNow(); }
}

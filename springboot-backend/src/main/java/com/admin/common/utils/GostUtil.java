package com.admin.common.utils;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.apache.bcel.generic.RET;

import java.util.Objects;

public class GostUtil {
    /** Null means the legacy, disabled setting. Validate even for internal callers. */
    public static int normalizeProxyProtocol(Integer version) {
        if (version == null) return 0;
        if (version < 0 || version > 2) throw new IllegalArgumentException("Proxy Protocol 仅支持关闭、v1 或 v2");
        return version;
    }

    /** Revision 2 confirms JSON numeric metadata is applied; older Agents can silently ignore it. */
    public static GostDto CheckForwardProxyProtocolSupport(Long nodeId, Integer version) {
        int selected = normalizeProxyProtocol(version);
        if (selected == 0) return null;
        GostDto result = WebSocketServer.send_msg(nodeId, new JSONObject(), "ForwardProxyProtocolCapabilities");
        if (result != null && "OK".equals(result.getMsg()) && result.getData() != null) {
            try {
                JSONObject capabilities = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
                JSONArray versions = capabilities.getJSONArray("proxyProtocolVersions");
                if (capabilities.getIntValue("forwardProxyProtocol") >= 2 && versions != null
                        && versions.toJavaList(Integer.class).contains(selected)) return null;
            } catch (RuntimeException malformedCapabilities) {
                // A bad/legacy response is not evidence that sending is supported.
            }
        }
        GostDto denied = new GostDto();
        denied.setMsg("入口节点未确认支持发送 Proxy Protocol v" + selected + "，请先升级节点后端或检查节点连接");
        return denied;
    }

    public static GostDto SetUserProtocolPolicy(Long nodeId, java.util.Collection<String> names, JSONObject policy) {
        JSONObject data = new JSONObject();
        data.put("services", names);
        data.put("policy", policy);
        return WebSocketServer.send_msg(nodeId, data, "SetUserProtocolPolicy");
    }

    private static GostDto checkPolicySupport(Long nodeId, JSONObject policy) {
        if (!com.admin.common.service.UserProtocolPolicyService.restricted(policy)) return null;
        GostDto result = WebSocketServer.send_msg(nodeId, new JSONObject(), "UserProtocolPolicyCapabilities");
        if (result != null && "OK".equals(result.getMsg()) && result.getData() != null) {
            JSONObject capabilities = JSONObject.parseObject(JSONObject.toJSONString(result.getData()));
            if (capabilities.getIntValue("userProtocolPolicy") >= 1) return null;
        }
        GostDto denied = new GostDto();
        denied.setMsg("入口节点未确认支持用户协议限制，请先升级节点后端或检查节点连接");
        return denied;
    }

    /** Endpoint syntax shared by listeners and relay chains. */
    public static String formatAddress(String host, Integer port) {
        if (host == null || port == null) return "";
        String address = host.trim();
        if (address.contains(":") && !(address.startsWith("[") && address.endsWith("]"))) {
            address = "[" + address + "]";
        }
        return address + ":" + port;
    }


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        return WebSocketServer.send_msg(node_id, data, "AddLimiters");
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        return WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        return AddService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, null);
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy) {
        return AddService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, policy, 0);
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy, Integer proxyProtocol) {
        return AddMissingService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy,
                interfaceName, policy, true, true, proxyProtocol);
    }

    /**
     * Restore only protocols absent from a complete node inventory. Reuse the
     * create command so a stale inventory cannot replace an existing service.
     */
    public static GostDto AddMissingService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy, boolean tcpMissing, boolean udpMissing) {
        return AddMissingService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy,
                interfaceName, policy, tcpMissing, udpMissing, 0);
    }

    public static GostDto AddMissingService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy, boolean tcpMissing, boolean udpMissing, Integer proxyProtocol) {
        normalizeProxyProtocol(proxyProtocol);
        if (!tcpMissing && !udpMissing) {
            GostDto result = new GostDto();
            result.setMsg("OK");
            return result;
        }
        GostDto unsupported = checkPolicySupport(node_id, policy);
        if (unsupported != null) return unsupported;
        if (tcpMissing) {
            unsupported = CheckForwardProxyProtocolSupport(node_id, proxyProtocol);
            if (unsupported != null) return unsupported;
        }
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            if (Objects.equals(protocol, "tcp") ? !tcpMissing : !udpMissing) continue;
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName);
            applyUserPolicy(service, policy);
            applyProxyProtocol(service, protocol, proxyProtocol);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        return UpdateService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, null);
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy) {
        return UpdateService(node_id, name, in_port, limiter, remoteAddr, fow_type, tunnel, strategy, interfaceName, policy, 0);
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName, JSONObject policy, Integer proxyProtocol) {
        normalizeProxyProtocol(proxyProtocol);
        GostDto unsupported = checkPolicySupport(node_id, policy);
        if (unsupported != null) return unsupported;
        unsupported = CheckForwardProxyProtocolSupport(node_id, proxyProtocol);
        if (unsupported != null) return unsupported;
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName);
            applyUserPolicy(service, policy);
            applyProxyProtocol(service, protocol, proxyProtocol);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "UpdateService");
    }

    private static void applyUserPolicy(JSONObject service, JSONObject policy) {
        if (policy == null) return;
        JSONObject metadata = service.getJSONObject("metadata");
        if (metadata == null) { metadata = new JSONObject(); service.put("metadata", metadata); }
        metadata.put("userProtocolPolicy", policy);
    }

    private static void applyProxyProtocol(JSONObject service, String protocol, Integer version) {
        int selected = normalizeProxyProtocol(version);
        if (!"tcp".equals(protocol) || selected == 0) return;
        // Send once from the trusted ingress socket, through the relay payload
        // when tunnelling. Never enable a listener that trusts client headers.
        service.getJSONObject("handler").put("metadata", JSONObject.of("proxyProtocol", selected));
    }

    public static GostDto DeleteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "DeleteService");
    }

    /** Old Agents reject an entire delete batch if any one protocol is absent. */
    public static GostDto DeleteServiceProtocol(Long nodeId, String name, String protocol) {
        if (!"tcp".equals(protocol) && !"udp".equals(protocol)) {
            throw new IllegalArgumentException("仅支持按 TCP 或 UDP 清理入口服务");
        }
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_" + protocol);
        data.put("services", services);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto AddRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,  String protocol, String strategy, String interfaceName) {
        JSONObject data = new JSONObject();
        data.put("name", name + "_tls");
        data.put("addr", ":" + out_port);

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            data.put("metadata", metadata);
        }


        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        data.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        data.put("listener", listener);
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }
        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }
        forwarder.put("nodes", nodes);
        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);

        data.put("forwarder", forwarder);
        JSONArray services = new JSONArray();
        services.add(data);
        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    public static GostDto UpdateRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,String protocol, String strategy, String interfaceName) {
        JSONObject data = new JSONObject();
        data.put("name", name + "_tls");
        data.put("addr", ":" + out_port);

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            data.put("metadata", metadata);
        }


        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        data.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        data.put("listener", listener);
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }
        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }
        forwarder.put("nodes", nodes);
        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);

        data.put("forwarder", forwarder);
        JSONArray services = new JSONArray();
        services.add(data);
        return WebSocketServer.send_msg(node_id, services, "UpdateService");
    }

    public static GostDto DeleteRemoteService(Long node_id, String name) {
        JSONArray data = new JSONArray();
        data.add(name + "_tls");
        JSONObject req = new JSONObject();
        req.put("services", data);
        return WebSocketServer.send_msg(node_id, req, "DeleteService");
    }

    public static GostDto PauseService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto PauseRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto AddChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        JSONObject dialer = new JSONObject();
        dialer.put("type", protocol);
        if (Objects.equals(protocol, "quic")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            metadata.put("ttl", "10s");
            dialer.put("metadata", metadata);
        }




        JSONObject connector = new JSONObject();
        connector.put("type", "relay");

        JSONObject node = new JSONObject();
        node.put("name", "node-" + name);
        node.put("addr", remoteAddr);
        node.put("connector", connector);
        node.put("dialer", dialer);

        if (StringUtils.isNotBlank(interfaceName)) {
            node.put("interface", interfaceName);
        }


        JSONArray nodes = new JSONArray();
        nodes.add(node);

        JSONObject hop = new JSONObject();
        hop.put("name", "hop-" + name);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", name + "_chains");
        data.put("hops", hops);

        return WebSocketServer.send_msg(node_id, data, "AddChains");
    }

    public static GostDto UpdateChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        JSONObject dialer = new JSONObject();
        dialer.put("type", protocol);

        if (Objects.equals(protocol, "quic")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            metadata.put("ttl", "10s");
            dialer.put("metadata", metadata);
        }


        JSONObject connector = new JSONObject();
        connector.put("type", "relay");

        JSONObject node = new JSONObject();
        node.put("name", "node-" + name);
        node.put("addr", remoteAddr);
        node.put("connector", connector);
        node.put("dialer", dialer);

        if (StringUtils.isNotBlank(interfaceName)) {
            node.put("interface", interfaceName);
        }

        JSONArray nodes = new JSONArray();
        nodes.add(node);

        JSONObject hop = new JSONObject();
        hop.put("name", "hop-" + name);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", name + "_chains");
        data.put("hops", hops);
        JSONObject req = new JSONObject();
        req.put("chain", name + "_chains");
        req.put("data", data);
       return WebSocketServer.send_msg(node_id, req, "UpdateChains");
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name + "_chains");
        return WebSocketServer.send_msg(node_id, data, "DeleteChains");
    }

    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createServiceConfig(String name, Integer in_port, Integer limiter, String remoteAddr, String protocol, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        JSONObject service = new JSONObject();
        service.put("name", name + "_" + protocol);
        if (Objects.equals(protocol, "tcp")){
            service.put("addr", formatAddress(tunnel.getTcpListenAddr(), in_port));
        }else {
            service.put("addr", formatAddress(tunnel.getUdpListenAddr(), in_port));
        }

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            service.put("metadata", metadata);
        }


        // 添加限流器配置
        if (limiter != null) {
            service.put("limiter", limiter.toString());
        }

        // 配置处理器
        JSONObject handler = createHandler(protocol, name, fow_type);
        service.put("handler", handler);

        // 配置监听器
        JSONObject listener = createListener(protocol);
        service.put("listener", listener);

        // 端口转发需要配置转发器
        if (isPortForwarding(fow_type)) {
            JSONObject forwarder = createForwarder(remoteAddr, strategy);
            service.put("forwarder", forwarder);
        }
        return service;
    }

    private static JSONObject createHandler(String protocol, String name, Integer fow_type) {
        JSONObject handler = new JSONObject();
        handler.put("type", protocol);

        // 隧道转发需要添加链配置
        if (isTunnelForwarding(fow_type)) {
            handler.put("chain", name + "_chains");
        }

        return handler;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }

        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    private static boolean isPortForwarding(Integer fow_type) {
        return fow_type != null && fow_type == 1;
    }

    private static boolean isTunnelForwarding(Integer fow_type) {
        return fow_type != null && fow_type != 1;
    }

}

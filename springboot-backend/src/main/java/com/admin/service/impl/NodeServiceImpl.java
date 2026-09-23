package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeInstallCommandDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <p>
 * 节点服务实现类
 * 提供节点的增删改查功能，包括节点创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {

    // ========== 常量定义 ==========
    
    /** 节点默认状态：启用 */
    private static final int NODE_STATUS_ACTIVE = 0;
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "节点创建成功";
    private static final String SUCCESS_UPDATE_MSG = "节点更新成功";
    private static final String SUCCESS_DELETE_MSG = "节点删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "节点创建失败";
    private static final String ERROR_UPDATE_MSG = "节点更新失败";
    private static final String ERROR_DELETE_MSG = "节点删除失败";
    private static final String ERROR_NODE_NOT_FOUND = "节点不存在";
    
    /** 隧道使用检查相关消息 */
    private static final String ERROR_IN_NODE_IN_USE = "该节点还有 %d 个隧道作为入口节点在使用，请先删除相关隧道";
    private static final String ERROR_OUT_NODE_IN_USE = "该节点还有 %d 个隧道作为出口节点在使用，请先删除相关隧道";
    
    /** 端口范围验证相关消息 */
    private static final String ERROR_PORT_STA_REQUIRED = "起始端口不能为空";
    private static final String ERROR_PORT_END_REQUIRED = "结束端口不能为空";
    private static final String ERROR_PORT_RANGE_INVALID = "端口必须在1-65535范围内";
    private static final String ERROR_PORT_ORDER_INVALID = "结束端口不能小于起始端口";
    private static final String DEFAULT_TCP_TUNING_PROFILE = "balanced";
    private static final String DDNS_RECORD_PATTERN = "(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$";

    // ========== 依赖注入 ==========
    
    @Resource
    private TunnelMapper tunnelMapper;

    @Resource
    private TunnelEntryNodeMapper tunnelEntryNodeMapper;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Value("${jwt-secret}")
    private String jwtSecret;

    private volatile AESCrypto ddnsCrypto;

    // ========== 公共接口实现 ==========

    /**
     * 创建新节点
     * 
     * @param nodeDto 节点创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createNode(NodeDto nodeDto) {
        Node node = buildNewNode(nodeDto);
        R installSettings = applyInstallSettings(node, null, nodeDto.getTcpTuningProfile(),
                nodeDto.getTcpTuningAutoEnabled(), nodeDto.getTcpTuningProfileMin(), nodeDto.getTcpTuningProfileMax(),
                nodeDto.getDdnsEnabled(), nodeDto.getCfApiToken(), nodeDto.getCfRecordName());
        if (installSettings.getCode() != 0) {
            return installSettings;
        }
        boolean result = this.save(node);
        return result ? R.ok(SUCCESS_CREATE_MSG) : R.err(ERROR_CREATE_MSG);
    }



    /**
     * 获取所有节点列表
     * 注意：返回结果中会隐藏节点密钥信息
     * 
     * @return 包含所有节点的响应对象
     */
    @Override
    public R getAllNodes() {
        List<Node> nodeList = this.list();
        hideNodeSecrets(nodeList);
        return R.ok(nodeList);
    }

    /**
     * 更新节点信息
     * 
     * @param nodeUpdateDto 节点更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        // 1. 验证节点是否存在
        Node node = this.getById(nodeUpdateDto.getId());
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        //1.1 如果节点在线 且传入更新的 http/tls/socks 任意一项与数据库不一致，则通过 WS 通知节点更新设置
        boolean online = node.getStatus() != null && node.getStatus() == 1;
        Integer newHttp = nodeUpdateDto.getHttp();
        Integer newTls = nodeUpdateDto.getTls();
        Integer newSocks = nodeUpdateDto.getSocks();

        boolean httpChanged = newHttp != null && !newHttp.equals(node.getHttp());
        boolean tlsChanged = newTls != null && !newTls.equals(node.getTls());
        boolean socksChanged = newSocks != null && !newSocks.equals(node.getSocks());

        if (online && (httpChanged || tlsChanged || socksChanged)) {
            JSONObject req = new JSONObject();
            req.put("http", newHttp);
            req.put("tls", newTls);
            req.put("socks", newSocks);

            GostDto gostResult = WebSocketServer.send_msg(node.getId(), req, "SetProtocol");
            if (!Objects.equals(gostResult.getMsg(), "OK")){
                return R.err(gostResult.getMsg());
            }
        }


        // 2. 构建更新对象并执行更新
        Node updateNode = buildUpdateNode(nodeUpdateDto);
        R installSettings = applyInstallSettings(updateNode, node, nodeUpdateDto.getTcpTuningProfile(),
                nodeUpdateDto.getTcpTuningAutoEnabled(), nodeUpdateDto.getTcpTuningProfileMin(), nodeUpdateDto.getTcpTuningProfileMax(),
                nodeUpdateDto.getDdnsEnabled(), nodeUpdateDto.getCfApiToken(), nodeUpdateDto.getCfRecordName());
        if (installSettings.getCode() != 0) {
            return installSettings;
        }
        boolean result = this.updateById(updateNode);

        // 更新隧道入口ip
        List<Tunnel> inNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", updateNode.getId()));
        if (!inNodeId.isEmpty()) {
            List<Tunnel> singleIngressTunnels = inNodeId.stream()
                    .filter(tunnel -> isSingleIngressTunnel(tunnel))
                    .collect(Collectors.toList());
            for (Tunnel tunnel : singleIngressTunnels) {
                tunnel.setInIp(updateNode.getIp());
            }
            if (!singleIngressTunnels.isEmpty()) {
                tunnelService.updateBatchById(singleIngressTunnels);
            }
        }

        // 更新服务器出口ip
        List<Tunnel> outNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", updateNode.getId()));
        if (!outNodeId.isEmpty()) {
            for (Tunnel tunnel : outNodeId) {
                tunnel.setOutIp(updateNode.getServerIp());
            }
            tunnelService.updateBatchById(outNodeId);
        }

        return result ? R.ok(SUCCESS_UPDATE_MSG) : R.err(ERROR_UPDATE_MSG);
    }

    /**
     * A multi-ingress tunnel stores its public addresses independently of a
     * node's management address. Editing a node must never overwrite those
     * user-configured addresses. The comma check keeps older data safe even
     * before the relation-table migration has run.
     */
    private boolean isSingleIngressTunnel(Tunnel tunnel) {
        if (tunnel.getInIp() != null && tunnel.getInIp().contains(",")) {
            return false;
        }
        return tunnelEntryNodeMapper.selectCount(
                new QueryWrapper<com.admin.entity.TunnelEntryNode>().eq("tunnel_id", tunnel.getId())) <= 1;
    }

    /**
     * 删除节点
     * 删除前会检查是否有隧道正在使用该节点
     * 
     * @param id 节点ID
     * @return 删除结果响应
     */
    @Override
    public R deleteNode(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 检查节点使用情况
        R usageCheckResult = checkNodeUsage(id);
        if (usageCheckResult.getCode() != 0) {
            return usageCheckResult;
        }

        // 3. 执行删除操作
        boolean result = this.removeById(id);
        return result ? R.ok(SUCCESS_DELETE_MSG) : R.err(ERROR_DELETE_MSG);
    }

    /**
     * 根据ID获取节点信息
     * 
     * @param id 节点ID
     * @return 节点对象
     * @throws RuntimeException 当节点不存在时抛出异常
     */
    @Override
    public Node getNodeById(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            throw new RuntimeException(ERROR_NODE_NOT_FOUND);
        }
        return node;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 构建新节点对象
     * 
     * @param nodeDto 节点创建DTO
     * @return 构建完成的节点对象
     */
    private Node buildNewNode(NodeDto nodeDto) {
        Node node = new Node();
        BeanUtils.copyProperties(nodeDto, node);
        
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        // 设置默认属性
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(NODE_STATUS_ACTIVE);
        
        // 设置时间戳
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);
        
        return node;
    }

    /**
     * 构建节点更新对象
     * 
     * @param nodeUpdateDto 节点更新DTO
     * @return 构建完成的更新对象
     */
    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setIp(nodeUpdateDto.getIp());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setPortSta(nodeUpdateDto.getPortSta());
        node.setPortEnd(nodeUpdateDto.getPortEnd());
        node.setHttp(nodeUpdateDto.getHttp());
        node.setTls(nodeUpdateDto.getTls());
        node.setSocks(nodeUpdateDto.getSocks());
        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        node.setUpdatedTime(System.currentTimeMillis());
        return node;
    }

    /**
     * Persists installation preferences at node creation/edit time. The token
     * is encrypted with a key derived from the panel JWT secret, so the node
     * table and API responses never contain the plaintext Cloudflare token.
     */
    private R applyInstallSettings(Node target, Node existing, String requestedProfile,
                                   Boolean requestedAutoEnabled, String requestedProfileMin, String requestedProfileMax,
                                   Boolean requestedDdnsEnabled, String requestedToken,
                                   String requestedRecordName) {
        String profile = requestedProfile == null
                ? (existing == null ? DEFAULT_TCP_TUNING_PROFILE : existing.getTcpTuningProfile())
                : requestedProfile;
        profile = normalizeTcpTuningProfile(profile);
        if (profile == null) {
            return R.err("TCP 调优档位无效");
        }
        target.setTcpTuningProfile(profile);

        boolean autoEnabled = requestedAutoEnabled != null
                ? requestedAutoEnabled
                : existing != null && Integer.valueOf(1).equals(existing.getTcpTuningAutoEnabled());
        target.setTcpTuningAutoEnabled(autoEnabled ? 1 : 0);
        if (autoEnabled) {
            String minProfile = normalizeTcpTuningProfile(requestedProfileMin == null
                    ? (existing == null ? null : existing.getTcpTuningProfileMin())
                    : requestedProfileMin);
            String maxProfile = normalizeTcpTuningProfile(requestedProfileMax == null
                    ? (existing == null ? null : existing.getTcpTuningProfileMax())
                    : requestedProfileMax);
            if (minProfile == null || maxProfile == null) {
                return R.err("启用动态 TCP 调优时必须选择有效的最低和最高档位");
            }
            if (tuningProfileRank(minProfile) > tuningProfileRank(maxProfile)) {
                return R.err("动态 TCP 调优的最低档位不能高于最高档位");
            }
            // The fixed value remains a safe fallback for older installation
            // commands and when an administrator later disables dynamic mode.
            target.setTcpTuningProfile(maxProfile);
            target.setTcpTuningProfileMin(minProfile);
            target.setTcpTuningProfileMax(maxProfile);
        } else {
            target.setTcpTuningProfileMin(null);
            target.setTcpTuningProfileMax(null);
        }

        boolean enabled = requestedDdnsEnabled != null
                ? requestedDdnsEnabled
                : existing != null && Integer.valueOf(1).equals(existing.getDdnsEnabled());
        target.setDdnsEnabled(enabled ? 1 : 0);
        if (!enabled) {
            target.setDdnsToken(null);
            target.setDdnsRecordName(null);
            return R.ok();
        }

        String recordName = StrUtil.isBlank(requestedRecordName)
                ? (existing == null ? null : existing.getDdnsRecordName())
                : requestedRecordName.trim().toLowerCase();
        if (StrUtil.isBlank(recordName) || !recordName.matches(DDNS_RECORD_PATTERN)) {
            return R.err("启用 DDNS 时必须填写有效的完整记录域名");
        }

        String encryptedToken;
        if (StrUtil.isBlank(requestedToken)) {
            encryptedToken = existing == null ? null : existing.getDdnsToken();
        } else {
            if (requestedToken.contains("\n") || requestedToken.contains("\r")) {
                return R.err("Cloudflare API Token 格式无效");
            }
            try {
                encryptedToken = getDdnsCrypto().encrypt(requestedToken.trim());
            } catch (RuntimeException exception) {
                return R.err("DDNS Token 加密失败，请检查面板密钥配置");
            }
        }
        if (StrUtil.isBlank(encryptedToken)) {
            return R.err("启用 DDNS 时必须填写 Cloudflare API Token");
        }

        target.setDdnsToken(encryptedToken);
        target.setDdnsRecordName(recordName);
        return R.ok();
    }

    private String normalizeTcpTuningProfile(String profile) {
        if (StrUtil.isBlank(profile)) {
            return DEFAULT_TCP_TUNING_PROFILE;
        }
        String normalized = profile.trim().toLowerCase();
        if ("tiny".equals(normalized) || "small".equals(normalized)
                || "balanced".equals(normalized) || "standard".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private int tuningProfileRank(String profile) {
        if ("tiny".equals(profile)) {
            return 1;
        }
        if ("small".equals(profile)) {
            return 2;
        }
        if ("balanced".equals(profile)) {
            return 3;
        }
        if ("standard".equals(profile)) {
            return 4;
        }
        return 0;
    }

    private AESCrypto getDdnsCrypto() {
        AESCrypto crypto = ddnsCrypto;
        if (crypto == null) {
            synchronized (this) {
                crypto = ddnsCrypto;
                if (crypto == null) {
                    crypto = new AESCrypto(jwtSecret + ":node-ddns:v1");
                    ddnsCrypto = crypto;
                }
            }
        }
        return crypto;
    }

    private String decryptDdnsToken(String encryptedToken) {
        try {
            return getDdnsCrypto().decryptString(encryptedToken);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 隐藏节点列表中的密钥信息
     * 
     * @param nodeList 节点列表
     */
    private void hideNodeSecrets(List<Node> nodeList) {
        nodeList.forEach(node -> {
            node.setSecret(null);
            node.setDdnsToken(null);
        });
    }


    /**
     * 检查节点使用情况
     * 验证是否有隧道正在使用该节点作为入口或出口节点
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkNodeUsage(Long nodeId) {
        // 检查入口节点使用情况
        R inNodeCheckResult = checkInNodeUsage(nodeId);
        if (inNodeCheckResult.getCode() != 0) {
            return inNodeCheckResult;
        }

        // 检查出口节点使用情况
        return checkOutNodeUsage(nodeId);
    }

    /**
     * 检查节点作为入口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkInNodeUsage(Long nodeId) {
        long multiIngressCount = tunnelEntryNodeMapper.selectCount(
                new QueryWrapper<com.admin.entity.TunnelEntryNode>().eq("node_id", nodeId));
        if (multiIngressCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, multiIngressCount);
            return R.err(errorMsg);
        }

        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("in_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 检查节点作为出口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkOutNodeUsage(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("out_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_OUT_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 获取节点安装命令
     * 根据节点信息生成对应的安装命令
     * 
     * @param id 节点ID
     * @return 包含安装命令的响应对象
     */
    @Override
    public R getInstallCommand(NodeInstallCommandDto commandDto) {
        // 1. 验证节点是否存在
        Node node = this.getById(commandDto.getId());
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 构建安装命令
        return buildInstallCommand(node, commandDto);
    }

    /**
     * 构建节点安装命令
     * 
     * @param node 节点对象
     * @return 格式化的安装命令
     */
    private R buildInstallCommand(Node node, NodeInstallCommandDto commandDto) {
        String panelUrl = normalizePanelUrl(commandDto.getPanelUrl());
        if (panelUrl == null) {
            return R.err("无法识别当前面板域名，请通过 HTTPS 域名访问面板后重试");
        }

        StringBuilder command = new StringBuilder();
        
        // 安装脚本始终从本增强版仓库获取，避免自动补号后重新装回上游旧 Agent。
        command.append("curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/install.sh")
               .append(" | bash -s -- ");
        
        // 前端自动传入当前 HTTPS 域名；节点通过 WSS/HTTPS 访问它，不再依赖公网后端端口。
        // 参数使用单引号转义，避免地址或密钥中的特殊字符破坏安装命令。
        command.append("--panel ").append(shellQuote(panelUrl))
               .append(" --token ").append(shellQuote(node.getSecret()));

        String tcpTuningProfile = normalizeTcpTuningProfile(node.getTcpTuningProfile());
        if (Integer.valueOf(1).equals(node.getTcpTuningAutoEnabled())) {
            String minProfile = normalizeTcpTuningProfile(node.getTcpTuningProfileMin());
            String maxProfile = normalizeTcpTuningProfile(node.getTcpTuningProfileMax());
            if (minProfile == null || maxProfile == null || tuningProfileRank(minProfile) > tuningProfileRank(maxProfile)) {
                return R.err("该节点的动态 TCP 调优上下限无效，请编辑节点后重新保存");
            }
            command.append(" --tcp-auto-tune")
                    .append(" --tcp-profile-min ").append(shellQuote(minProfile))
                    .append(" --tcp-profile-max ").append(shellQuote(maxProfile));
        } else {
            command.append(" --tcp-profile ").append(shellQuote(
                    tcpTuningProfile == null ? DEFAULT_TCP_TUNING_PROFILE : tcpTuningProfile));
        }

        if (Integer.valueOf(1).equals(node.getDdnsEnabled())) {
            String token = decryptDdnsToken(node.getDdnsToken());
            String recordName = node.getDdnsRecordName();
            if (StrUtil.isBlank(token) || StrUtil.isBlank(recordName) || !recordName.matches(DDNS_RECORD_PATTERN)) {
                return R.err("该节点的 DDNS 配置不完整或无法解密，请编辑节点后重新保存");
            }
            command.append(" --cf-api-token ").append(shellQuote(token))
                   .append(" --cf-record ").append(shellQuote(recordName));
        } else {
            command.append(" --disable-ddns");
        }
        return R.ok(command.toString());
    }

    private String shellQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\"'\"'") + "'";
    }

    /**
     * 只接受浏览器 origin 形式的面板地址。这样节点始终使用已公开的 HTTPS 域名，
     * 不会把内部 IP、查询参数或路径写进节点安装命令。
     */
    private String normalizePanelUrl(String panelUrl) {
        if (StrUtil.isBlank(panelUrl) || panelUrl.contains("\n") || panelUrl.contains("\r")) {
            return null;
        }
        try {
            URI uri = new URI(panelUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!("https".equals(scheme) || "http".equals(scheme))
                    || StrUtil.isBlank(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }
            return new URI(scheme, null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 验证端口范围的有效性
     * 
     * @param portSta 起始端口
     * @param portEnd 结束端口
     * @throws RuntimeException 当端口范围无效时抛出异常
     */
    private void validatePortRange(Integer portSta, Integer portEnd) {
        // 检查起始端口是否为空
        if (portSta == null) {
            throw new RuntimeException(ERROR_PORT_STA_REQUIRED);
        }
        
        // 检查结束端口是否为空
        if (portEnd == null) {
            throw new RuntimeException(ERROR_PORT_END_REQUIRED);
        }
        
        // 检查端口范围是否在有效区间内
        if (portSta < 1 || portSta > 65535 || portEnd < 1 || portEnd > 65535) {
            throw new RuntimeException(ERROR_PORT_RANGE_INVALID);
        }
        
        // 检查端口顺序是否正确
        if (portEnd < portSta) {
            throw new RuntimeException(ERROR_PORT_ORDER_INVALID);
        }
    }

}

package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.admin.common.dto.ForwardSyncSummary;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.service.ForwardPortReservationService;
import com.admin.common.task.ForwardSyncTaskService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    // 常量定义
    private static final String GOST_SUCCESS_MSG = "OK";
    private static final String GOST_NOT_FOUND_MSG = "not found";
    private static final int ADMIN_ROLE_ID = 0;
    private static final int TUNNEL_TYPE_PORT_FORWARD = 1;
    private static final int TUNNEL_TYPE_TUNNEL_FORWARD = 2;
    private static final int FORWARD_STATUS_ACTIVE = 1;
    private static final int FORWARD_STATUS_PAUSED = 0;
    private static final int FORWARD_STATUS_ERROR = -1;
    private static final int TUNNEL_STATUS_ACTIVE = 1;
    private static final int USER_STATUS_ACTIVE = 1;

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    TunnelEntryNodeMapper tunnelEntryNodeMapper;

    @Resource
    @Lazy
    private ForwardSyncTaskService forwardSyncTaskService;

    @Resource
    private ForwardPortReservationService forwardPortReservationService;

    @Resource
    private VpsHostService vpsHostService;


    @Override
    public R createForward(ForwardDto forwardDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法创建转发");
        }

        // 3. 普通用户权限和限制检查
        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        R vpsAccess = validateVpsHostAccess(currentUser, forwardDto.getVpsHostId());
        if (vpsAccess.getCode() != 0) {
            return vpsAccess;
        }

        // The availability query is only an optimization. The reservation row
        // below is the authoritative database race check, so simultaneous
        // requests cannot both configure the same node port.
        String lastReservationError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            PortAllocation portAllocation = allocatePorts(tunnel, forwardDto.getInPort());
            if (portAllocation.isHasError()) {
                return R.err(portAllocation.getErrorMessage());
            }

            Forward forward = createForwardEntity(forwardDto, currentUser, portAllocation);
            if (!this.save(forward)) {
                return R.err("端口转发创建失败");
            }

            ForwardPortReservationService.ReservationLease reservation =
                    forwardPortReservationService.reserve(forward, tunnel);
            if (!reservation.isSuccess()) {
                this.removeById(forward.getId());
                lastReservationError = reservation.getMessage();
                // A random automatic allocation can safely try a fresh port.
                // A specified port will be rejected by the next availability
                // read with the same clear message.
                continue;
            }

            NodeInfo nodeInfo = getRequiredNodes(tunnel);
            if (nodeInfo.isHasError()) {
                forwardPortReservationService.rollback(reservation);
                this.removeById(forward.getId());
                return R.err(nodeInfo.getErrorMessage());
            }

            R gostResult = createGostServices(forward, tunnel, permissionResult.getLimiter(), nodeInfo,
                    permissionResult.getUserTunnel());
            if (gostResult.getCode() != 0) {
                forwardPortReservationService.rollback(reservation);
                this.removeById(forward.getId());
                return gostResult;
            }

            forwardPortReservationService.commit(reservation);
            return R.ok();
        }
        return R.err(lastReservationError == null ? "端口已被并发占用，请重试" : lastReservationError);
    }

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();

        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        Map<Long, ForwardSyncSummary> syncSummaries = forwardSyncTaskService.activeSummaries(
                forwardList.stream().map(ForwardWithTunnelDto::getId).collect(Collectors.toList()));
        for (ForwardWithTunnelDto forward : forwardList) {
            ForwardSyncSummary summary = syncSummaries.get(forward.getId());
            if (summary == null) continue;
            forward.setSyncOperation(summary.getOperation());
            forward.setSyncState(summary.getRetriedTasks() != null && summary.getRetriedTasks() > 0
                    ? "partial" : "syncing");
            forward.setSyncError(summary.getLastError());
        }

        return R.ok(forwardList);
    }

    @Override
    public R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }

        // 3. 检查隧道是否存在和可用
        Tunnel tunnel = validateTunnel(forwardUpdateDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }
        if (tunnel.getStatus() != TUNNEL_STATUS_ACTIVE) {
            return R.err("隧道已禁用，无法更新转发");
        }
        boolean tunnelChanged = isTunnelChanged(existForward, forwardUpdateDto);

        // 4. Every save recreates the GOST service, even when its tunnel does
        // not change. Recheck the forward owner's effective permission on every
        // edit so an expired, disabled or exhausted assignment cannot be
        // revived by changing an unrelated field. The result also carries the
        // speed limiter that must be preserved on an in-place update.
        UserPermissionResult permissionResult = checkForwardOwnerPermissions(
                currentUser, existForward, tunnel, forwardUpdateDto.getId());
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        Long nextVpsHostId = resolveVpsHostId(forwardUpdateDto, existForward);
        R vpsAccess = validateUpdatedForwardVpsHost(existForward, nextVpsHostId);
        if (vpsAccess.getCode() != 0) {
            return vpsAccess;
        }

        // 5. Keep the existing service-name convention for administrator-owned
        // forwards while using the validated assignment for regular users.
        UserTunnel userTunnel = permissionResult.getUserTunnel();
        if (userTunnel == null) {
            userTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        // 6. 更新Forward对象
        Forward updatedForward = updateForwardEntity(forwardUpdateDto, existForward, tunnel);
        updatedForward.setVpsHostId(nextVpsHostId);
        boolean portLayoutChanged = tunnelChanged
                || !Objects.equals(updatedForward.getInPort(), existForward.getInPort())
                || !Objects.equals(updatedForward.getOutPort(), existForward.getOutPort());
        ForwardPortReservationService.ReservationLease reservation = null;
        if (portLayoutChanged) {
            reservation = forwardPortReservationService.reserve(updatedForward, tunnel);
            if (!reservation.isSuccess()) {
                return R.err(reservation.getMessage());
            }
        }

        // 7. 获取所需的节点信息
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            forwardPortReservationService.rollback(reservation);
            return R.err(nodeInfo.getErrorMessage());
        }

        // 8. 调用Gost服务更新转发
        R gostResult;
        if (tunnelChanged) {
            // 隧道变化时：先删除原配置，再创建新配置
            gostResult = updateGostServicesWithTunnelChange(existForward, updatedForward, tunnel,
                    permissionResult.getLimiter(), nodeInfo, userTunnel);
        } else {
            // 隧道未变化时：直接更新配置
            gostResult = updateGostServices(updatedForward, tunnel, permissionResult.getLimiter(), nodeInfo, userTunnel);
        }

        if (gostResult.getCode() != 0) {
            forwardPortReservationService.rollback(reservation);
            return gostResult;
        }
        updatedForward.setStatus(1);
        // 9. 保存更新
        boolean result = this.updateById(updatedForward);
        if (result) {
            forwardPortReservationService.commit(reservation);
        } else {
            forwardPortReservationService.rollback(reservation);
        }
        return result ? R.ok("端口转发更新成功") : R.err("端口转发更新失败");
    }

    @Override
    public R deleteForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }
        return forwardSyncTaskService.requestDelete(forward.getId());
    }

    @Override
    public R pauseForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_PAUSED, "暂停", "PauseService");
    }

    @Override
    public R resumeForward(Long id) {
        return changeForwardStatus(id, FORWARD_STATUS_ACTIVE, "恢复", "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }
        // Keep the legacy endpoint, but it is no longer allowed to orphan a
        // live service. It now enters the same durable cleanup workflow.
        return forwardSyncTaskService.requestDelete(forward.getId());
    }

    /**
     * 改变转发状态（暂停/恢复）
     */
    private R changeForwardStatus(Long id, int targetStatus, String operation, String gostMethod) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // A durable per-endpoint operation avoids treating a multi-ingress
        // forward as safely paused/resumed when only the first node replied.
        // Resume eligibility is evaluated from the forward owner, not from
        // the administrator who happened to click the switch.
        return targetStatus == FORWARD_STATUS_ACTIVE
                ? forwardSyncTaskService.requestResume(forward.getId())
                : forwardSyncTaskService.requestPause(forward.getId());
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        return diagnoseForwardInternal(forward);
    }

    @Override
    public R diagnoseForwardForSystem(Long id) {
        Forward forward = this.getById(id);
        if (forward == null) {
            return R.err("转发不存在");
        }
        return diagnoseForwardInternal(forward);
    }

    private R diagnoseForwardInternal(Forward forward) {
        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取全部入口节点信息
        List<Node> inNodes = getEntryNodes(tunnel);
        if (inNodes.isEmpty()) {
            return R.err("入口节点不存在");
        }


        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");
        // 6. 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }

                for (Node inNode : inNodes) {
                    results.add(performTcpPingDiagnosis(inNode, targetIp, targetPort, "入口->目标"));
                }
            }
        } else {
            // 隧道转发：入口TCP ping出口，出口TCP ping目标
            Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return R.err("出口节点不存在");
            }

            // 入口TCP ping出口（使用转发的出口端口）
            for (Node inNode : inNodes) {
                results.add(performTcpPingDiagnosis(inNode, outNode.getServerIp(), forward.getOutPort(), "入口->出口"));
            }

            // 出口TCP ping目标
            for (String remoteAddress : remoteAddresses) {
                // 提取IP和端口
                String targetIp = extractIpFromAddress(remoteAddress);
                int targetPort = extractPortFromAddress(remoteAddress);
                if (targetIp == null || targetPort == -1) {
                    return R.err("无法解析目标地址: " + remoteAddress);
                }
                DiagnosisResult outToTargetResult = performTcpPingDiagnosis(outNode, targetIp, targetPort, "出口->目标");
                results.add(outToTargetResult);
            }

        }

        // 7. 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", forward.getId());
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == TUNNEL_TYPE_PORT_FORWARD ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    public R updateForwardOrder(Map<String, Object> params) {
        try {
            // 1. 获取当前用户信息
            UserInfo currentUser = getCurrentUserInfo();

            // 2. 验证参数
            if (!params.containsKey("forwards")) {
                return R.err("缺少forwards参数");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
            if (forwardsList == null || forwardsList.isEmpty()) {
                return R.err("forwards参数不能为空");
            }

            // 3. 验证用户权限（只能更新自己的转发）
            if (currentUser.getRoleId() != ADMIN_ROLE_ID) {
                // 普通用户只能更新自己的转发
                List<Long> forwardIds = forwardsList.stream()
                        .map(item -> Long.valueOf(item.get("id").toString()))
                        .collect(Collectors.toList());

                // 检查所有转发是否属于当前用户
                QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
                queryWrapper.in("id", forwardIds);
                queryWrapper.eq("user_id", currentUser.getUserId());

                long count = this.count(queryWrapper);
                if (count != forwardIds.size()) {
                    return R.err("只能更新自己的转发排序");
                }
            }

            // 4. 批量更新排序
            List<Forward> forwardsToUpdate = new ArrayList<>();
            for (Map<String, Object> forwardData : forwardsList) {
                Long id = Long.valueOf(forwardData.get("id").toString());
                Integer inx = Integer.valueOf(forwardData.get("inx").toString());

                Forward forward = new Forward();
                forward.setId(id);
                forward.setInx(inx);
                forwardsToUpdate.add(forward);
            }

            // 5. 执行批量更新
            boolean success = this.updateBatchById(forwardsToUpdate);
            if (success) {
                log.info("用户 {} 更新了 {} 个转发的排序", currentUser.getUserName(), forwardsToUpdate.size());
                return R.ok("排序更新成功");
            } else {
                return R.err("排序更新失败");
            }

        } catch (Exception e) {
            log.error("更新转发排序失败", e);
            return R.err("更新排序时发生错误: " + e.getMessage());
        }
    }

    /**
     * 从地址字符串中提取IP地址
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    /**
     * 从地址字符串中提取端口号
     * 支持格式: ip:port, [ipv6]:port, domain:port
     */
    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    /**
     * 执行TCP ping诊断
     *
     * @param node        执行TCP ping的节点
     * @param targetIp    目标IP地址
     * @param port        目标端口
     * @param description 诊断描述
     * @return 诊断结果
     */
    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 2);
            tcpPingData.put("timeout", 3000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    /**
     * 获取当前用户信息
     */
    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    /**
     * 验证隧道是否存在
     */
    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    /**
     * 验证转发是否存在且用户有权限访问
     */
    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != ADMIN_ROLE_ID &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    /**
     * 获取所需的节点信息
     */
    private NodeInfo getRequiredNodes(Tunnel tunnel) {
        List<Node> inNodes = getEntryNodes(tunnel);
        if (inNodes.isEmpty()) {
            return NodeInfo.error("入口节点不存在");
        }

        Node outNode = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outNode = nodeService.getNodeById(tunnel.getOutNodeId());
            if (outNode == null) {
                return NodeInfo.error("出口节点不存在");
            }
        }

        return NodeInfo.success(inNodes, outNode);
    }

    private List<Node> getEntryNodes(Tunnel tunnel) {
        List<TunnelEntryNode> entries = tunnelEntryNodeMapper.selectList(
                new QueryWrapper<TunnelEntryNode>().eq("tunnel_id", tunnel.getId()).orderByAsc("id"));
        LinkedHashSet<Long> nodeIds = entries.stream()
                .map(TunnelEntryNode::getNodeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (nodeIds.isEmpty() && tunnel.getInNodeId() != null) {
            nodeIds.add(tunnel.getInNodeId());
        }

        List<Node> result = new ArrayList<>();
        for (Long nodeId : nodeIds) {
            try {
                Node node = nodeService.getNodeById(nodeId);
                if (node == null) {
                    return Collections.emptyList();
                }
                result.add(node);
            } catch (RuntimeException ignored) {
                return Collections.emptyList();
            }
        }
        return result;
    }

    /**
     * Resolve the forward owner before an edit. Administrators may continue to
     * manage their own infrastructure without an assignment, but an
     * administrator editing somebody else's forward must be subject to exactly
     * the same entitlement checks as that forward owner.
     */
    private UserPermissionResult checkForwardOwnerPermissions(UserInfo operator, Forward forward,
                                                               Tunnel tunnel, Long excludeForwardId) {
        if (operator.getRoleId() == ADMIN_ROLE_ID
                && Objects.equals(operator.getUserId(), forward.getUserId())) {
            return UserPermissionResult.success(null, null);
        }

        User owner = userService.getById(forward.getUserId());
        if (owner == null) {
            return UserPermissionResult.error("转发归属用户不存在");
        }
        return checkUserPermissions(new UserInfo(owner.getId().intValue(), owner.getRoleId(), owner.getUser()),
                tunnel, excludeForwardId);
    }

    /**
     * 检查用户权限和限制
     */
    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == ADMIN_ROLE_ID) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo == null) {
            return UserPermissionResult.error("用户不存在");
        }
        if (!Objects.equals(userInfo.getStatus(), USER_STATUS_ACTIVE)) {
            return UserPermissionResult.error("用户已到期或被禁用");
        }
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // Flow is stored as a quota in GB and the used counters are stored in
        // bytes. Checking only whether a quota was configured allowed an edit
        // to restart a forward after its quota had already been consumed.
        if (userInfo.getFlow() == null || userInfo.getFlow() <= 0
                || userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() == null || userTunnel.getFlow() <= 0
                || userTunnel.getFlow() * BYTES_TO_GB <= userTunnel.getInFlow() + userTunnel.getOutFlow()) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    /**
     * 检查用户转发数量限制
     */
    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        QueryWrapper<Forward> userQuery = new QueryWrapper<Forward>().eq("user_id", userId);
        if (excludeForwardId != null) {
            userQuery.ne("id", excludeForwardId);
        }
        long userForwardCount = this.count(userQuery);
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    /**
     * 检查用户流量限制
     */
    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort) {
        return allocatePorts(tunnel, specifiedInPort, null);
    }

    /**
     * 分配端口
     */
    private PortAllocation allocatePorts(Tunnel tunnel, Integer specifiedInPort, Long excludeForwardId) {
        Integer inPort;

        if (specifiedInPort != null) {
            // 用户指定了入口端口，需要检查是否可用
            if (!isInPortAvailable(tunnel, specifiedInPort, excludeForwardId)) {
                return PortAllocation.error("指定的入口端口 " + specifiedInPort + " 已被占用或不在允许范围内");
            }
            inPort = specifiedInPort;
        } else {
            // 用户未指定端口时自动分配
            inPort = allocateInPort(tunnel, excludeForwardId);
            if (inPort == null) {
                return PortAllocation.error("隧道入口端口已满，无法分配新端口");
            }
        }

        Integer outPort = null;
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            outPort = allocateOutPort(tunnel, excludeForwardId);
            if (outPort == null) {
                return PortAllocation.error("隧道出口端口已满，无法分配新端口");
            }
        }

        return PortAllocation.success(inPort, outPort);
    }

    /**
     * 创建Forward实体对象
     */
    private Forward createForwardEntity(ForwardDto forwardDto, UserInfo currentUser, PortAllocation portAllocation) {
        Forward forward = new Forward();
        // 先复制DTO的属性，再设置其他属性，避免被覆盖
        BeanUtils.copyProperties(forwardDto, forward);
        forward.setStatus(FORWARD_STATUS_ACTIVE);
        forward.setInPort(portAllocation.getInPort());
        forward.setOutPort(portAllocation.getOutPort());
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * 更新Forward实体对象
     */
    private Forward updateForwardEntity(ForwardUpdateDto forwardUpdateDto, Forward existForward, Tunnel tunnel) {
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardUpdateDto, forward);

        // The owner is established when the forward is created. An
        // administrator may operate another user's rule, but an edit must not
        // silently transfer ownership simply because userId came from a form.
        forward.setUserId(existForward.getUserId());
        forward.setUserName(existForward.getUserName());

        // 处理端口分配逻辑
        boolean tunnelChanged = !existForward.getTunnelId().equals(forwardUpdateDto.getTunnelId());
        boolean inPortChanged = forwardUpdateDto.getInPort() != null &&
                !Objects.equals(forwardUpdateDto.getInPort(), existForward.getInPort());

        if (tunnelChanged || inPortChanged) {
            // 隧道变化或入口端口变化时需要重新分配
            Integer specifiedInPort = forwardUpdateDto.getInPort();
            // 如果没有指定新端口但隧道未变化，保持原端口
            if (specifiedInPort == null && !tunnelChanged) {
                specifiedInPort = existForward.getInPort();
            }

            PortAllocation portAllocation = allocatePorts(tunnel, specifiedInPort, forwardUpdateDto.getId());
            if (portAllocation.isHasError()) {
                throw new RuntimeException(portAllocation.getErrorMessage());
            }
            forward.setInPort(portAllocation.getInPort());
            forward.setOutPort(portAllocation.getOutPort());
        } else {
            // 隧道和端口都未变化，保持原端口
            forward.setInPort(existForward.getInPort());
            forward.setOutPort(existForward.getOutPort());
        }

        forward.setUpdatedTime(System.currentTimeMillis());
        return forward;
    }

    /**
     * Resolves the optional VPS association without breaking older clients
     * that do not include the new field on an edit request.
     */
    private Long resolveVpsHostId(ForwardUpdateDto updateDto, Forward existForward) {
        if (Boolean.TRUE.equals(updateDto.getClearVpsHost())) {
            return null;
        }
        return updateDto.getVpsHostId() == null ? existForward.getVpsHostId() : updateDto.getVpsHostId();
    }

    /**
     * A VPS association is descriptive metadata, but it must still be checked
     * at creation time so a user cannot attach someone else's SSH host to a
     * rule. The target address remains independently validated by the normal
     * forwarding flow.
     */
    private R validateVpsHostAccess(UserInfo user, Long vpsHostId) {
        if (vpsHostId == null) {
            return R.ok();
        }
        if (user == null || user.getUserId() == null) {
            return R.err("当前登录用户无效");
        }
        boolean administrator = Objects.equals(user.getRoleId(), ADMIN_ROLE_ID);
        VpsHost host = vpsHostService.findOperableHost(user.getUserId().longValue(), administrator, vpsHostId);
        return host == null
                ? R.err("关联 VPS 不存在、已移除或当前用户无权使用")
                : R.ok();
    }

    /**
     * Editing a regular user's rule is evaluated as that rule's owner even
     * when an administrator clicks save. This keeps assigned VPS boundaries
     * intact. A stale link to a VPS that was later removed is retained as
     * history and does not prevent unrelated forward edits; any new link is
     * always re-authorized.
     */
    private R validateUpdatedForwardVpsHost(Forward existForward, Long nextVpsHostId) {
        if (Objects.equals(existForward.getVpsHostId(), nextVpsHostId)) {
            return R.ok();
        }
        if (nextVpsHostId == null) {
            return R.ok();
        }
        User owner = userService.getById(existForward.getUserId());
        if (owner == null) {
            return R.err("转发归属用户不存在");
        }
        return validateVpsHostAccess(new UserInfo(owner.getId().intValue(), owner.getRoleId(), owner.getUser()),
                nextVpsHostId);
    }

    /**
     * 创建Gost服务
     */
    private R createGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
        List<Node> configuredInNodes = new ArrayList<>();

        // 隧道转发需要创建链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            for (Node inNode : nodeInfo.getInNodes()) {
                R chainResult = createChainService(inNode, serviceName, tunnel.getOutIp(), forward.getOutPort(), tunnel.getProtocol(), tunnel.getInterfaceName());
                if (chainResult.getCode() != 0) {
                    configuredInNodes.forEach(node -> GostUtil.DeleteChains(node.getId(), serviceName));
                    return R.err("入口节点 " + inNode.getName() + " 创建链失败：" + chainResult.getMsg());
                }
                configuredInNodes.add(inNode);
            }

            R remoteResult = createRemoteService(nodeInfo.getOutNode(), serviceName, forward, tunnel.getProtocol(), forward.getInterfaceName());
            if (remoteResult.getCode() != 0) {
                configuredInNodes.forEach(node -> GostUtil.DeleteChains(node.getId(), serviceName));
                GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                return remoteResult;
            }
        }

        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }


        for (Node inNode : nodeInfo.getInNodes()) {
            R serviceResult = createMainService(inNode, serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
            if (serviceResult.getCode() != 0) {
                configuredInNodes.forEach(node -> GostUtil.DeleteService(node.getId(), serviceName));
                if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
                    nodeInfo.getInNodes().forEach(node -> GostUtil.DeleteChains(node.getId(), serviceName));
                    if (nodeInfo.getOutNode() != null) {
                        GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                    }
                }
                return R.err("入口节点 " + inNode.getName() + " 创建服务失败：" + serviceResult.getMsg());
            }
            configuredInNodes.add(inNode);
        }
        return R.ok();
    }

    /**
     * 更新Gost服务
     */
    private R updateGostServices(Forward forward, Tunnel tunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // 隧道转发需要更新链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            for (Node inNode : nodeInfo.getInNodes()) {
                R chainResult = updateChainService(inNode, serviceName, tunnel.getOutIp(), forward.getOutPort(), tunnel.getProtocol(), tunnel.getInterfaceName());
                if (chainResult.getCode() != 0) {
                    updateForwardStatusToError(forward);
                    return R.err("入口节点 " + inNode.getName() + " 更新链失败：" + chainResult.getMsg());
                }
            }

            R remoteResult = updateRemoteService(nodeInfo.getOutNode(), serviceName, forward, tunnel.getProtocol(), forward.getInterfaceName());
            if (remoteResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return remoteResult;
            }
        }
        String interfaceName = null;
        // 创建主服务
        if (tunnel.getType() != TUNNEL_TYPE_TUNNEL_FORWARD) { // 不是隧道转发服务才会存在网络接口
            interfaceName = forward.getInterfaceName();
        }
        // 更新主服务
        for (Node inNode : nodeInfo.getInNodes()) {
            R serviceResult = updateMainService(inNode, serviceName, forward, limiter, tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
            if (serviceResult.getCode() != 0) {
                updateForwardStatusToError(forward);
                return R.err("入口节点 " + inNode.getName() + " 更新服务失败：" + serviceResult.getMsg());
            }
        }

        return R.ok();
    }

    /**
     * 隧道变化时更新Gost服务：先删除原配置，再创建新配置
     */
    private R updateGostServicesWithTunnelChange(Forward existForward, Forward updatedForward, Tunnel newTunnel, Integer limiter, NodeInfo nodeInfo, UserTunnel userTunnel) {
        // 1. 获取原隧道信息
        Tunnel oldTunnel = tunnelService.getById(existForward.getTunnelId());
        if (oldTunnel == null) {
            return R.err("原隧道不存在，无法删除旧配置");
        }

        // 2. 删除原有的Gost服务配置
        R deleteResult = deleteOldGostServices(existForward, oldTunnel);
        if (deleteResult.getCode() != 0) {
            // 删除失败时记录日志，但不影响后续创建（可能原配置已不存在）
            log.info("删除原隧道{}的Gost配置失败: {}", oldTunnel.getId(), deleteResult.getMsg());
        }

        // 3. 创建新的Gost服务配置
        R createResult = createGostServices(updatedForward, newTunnel, limiter, nodeInfo, userTunnel);
        if (createResult.getCode() != 0) {
            updateForwardStatusToError(updatedForward);
            return R.err("创建新隧道配置失败: " + createResult.getMsg());
        }

        return R.ok();
    }

    /**
     * 删除原有的Gost服务（隧道变化时专用）
     */
    private R deleteOldGostServices(Forward forward, Tunnel oldTunnel) {
        // 获取原隧道的用户隧道关系
        UserTunnel oldUserTunnel = getUserTunnel(forward.getUserId(), oldTunnel.getId().intValue());
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), oldUserTunnel);

        // 获取原隧道的节点信息
        NodeInfo oldNodeInfo = getRequiredNodes(oldTunnel);

        // Every ingress has the same main service name on its own node.
        if (!oldNodeInfo.isHasError()) {
            for (Node inNode : oldNodeInfo.getInNodes()) {
                GostDto serviceResult = GostUtil.DeleteService(inNode.getId(), serviceName);
                if (!isGostOperationSuccess(serviceResult)) {
                    log.info("删除节点 {} 的主服务失败: {}", inNode.getId(), serviceResult.getMsg());
                }
            }
        }

        // 如果原隧道是隧道转发类型，需要删除链和远程服务
        if (oldTunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            // 删除链服务
            if (!oldNodeInfo.isHasError()) {
                for (Node inNode : oldNodeInfo.getInNodes()) {
                    GostDto chainResult = GostUtil.DeleteChains(inNode.getId(), serviceName);
                    if (!isGostOperationSuccess(chainResult)) {
                        log.info("删除节点 {} 的链服务失败: {}", inNode.getId(), chainResult.getMsg());
                    }
                }
            }

            // 删除远程服务（即使节点信息获取失败，也要尝试删除）
            Node outNode = null;
            if (!oldNodeInfo.isHasError()) {
                outNode = oldNodeInfo.getOutNode();
            } else {
                // 即使获取节点信息失败，也尝试直接获取出口节点来删除远程服务
                outNode = nodeService.getNodeById(oldTunnel.getOutNodeId());
            }

            if (outNode != null) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(outNode.getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    log.info("删除远程服务失败: {}", remoteResult.getMsg());
                }
            }
        }

        return R.ok();
    }

    /**
     * 删除Gost服务
     */
    private R deleteGostServices(Forward forward, Tunnel tunnel, NodeInfo nodeInfo, UserTunnel userTunnel) {
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);

        // All ingress nodes own a copy of the service. Do not remove the DB row
        // until each of them has acknowledged deletion.
        for (Node inNode : nodeInfo.getInNodes()) {
            GostDto serviceResult = GostUtil.DeleteService(inNode.getId(), serviceName);
            if (!isGostOperationSuccess(serviceResult)) {
                return R.err("入口节点 " + inNode.getName() + " 删除服务失败：" + serviceResult.getMsg());
            }
        }

        // 隧道转发需要删除链和远程服务
        if (tunnel.getType() == TUNNEL_TYPE_TUNNEL_FORWARD) {
            for (Node inNode : nodeInfo.getInNodes()) {
                GostDto chainResult = GostUtil.DeleteChains(inNode.getId(), serviceName);
                if (!isGostOperationSuccess(chainResult)) {
                    return R.err("入口节点 " + inNode.getName() + " 删除链失败：" + chainResult.getMsg());
                }
            }

            if (nodeInfo.getOutNode() != null) {
                GostDto remoteResult = GostUtil.DeleteRemoteService(nodeInfo.getOutNode().getId(), serviceName);
                if (!isGostOperationSuccess(remoteResult)) {
                    return R.err(remoteResult.getMsg());
                }
            }
        }

        return R.ok();
    }

    /**
     * 创建链服务
     */
    private R createChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto result = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建远程服务
     */
    private R createRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        GostDto result = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 创建主服务
     */
    private R createMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新链服务
     */
    private R updateChainService(Node inNode, String serviceName, String outIp, Integer outPort, String protocol, String interfaceName) {
        // 创建新链
        String remoteAddr = outIp + ":" + outPort;
        if (outIp.contains(":")) {
            remoteAddr = "[" + outIp + "]:" + outPort;
        }
        GostDto createResult = GostUtil.UpdateChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddChains(inNode.getId(), serviceName, remoteAddr, protocol, interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新远程服务
     */
    private R updateRemoteService(Node outNode, String serviceName, Forward forward, String protocol, String interfaceName) {
        // 创建新远程服务
        GostDto createResult = GostUtil.UpdateRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        if (createResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            createResult = GostUtil.AddRemoteService(outNode.getId(), serviceName, forward.getOutPort(), forward.getRemoteAddr(), protocol, forward.getStrategy(), interfaceName);
        }
        return isGostOperationSuccess(createResult) ? R.ok() : R.err(createResult.getMsg());
    }

    /**
     * 更新主服务
     */
    private R updateMainService(Node inNode, String serviceName, Forward forward, Integer limiter, Integer tunnelType, Tunnel tunnel, String strategy, String interfaceName) {
        GostDto result = GostUtil.UpdateService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);

        if (result.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            result = GostUtil.AddService(inNode.getId(), serviceName, forward.getInPort(), limiter, forward.getRemoteAddr(), tunnelType, tunnel, strategy, interfaceName);
        }

        return isGostOperationSuccess(result) ? R.ok() : R.err(result.getMsg());
    }

    /**
     * 更新转发状态为错误
     */
    private void updateForwardStatusToError(Forward forward) {
        if (forward == null || forward.getId() == null) return;
        UpdateWrapper<Forward> statusUpdate = new UpdateWrapper<>();
        statusUpdate.eq("id", forward.getId())
                .set("status", FORWARD_STATUS_ERROR)
                .set("updated_time", System.currentTimeMillis());
        this.update(null, statusUpdate);
    }

    /**
     * 获取用户隧道关系
     */
    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    /**
     * 检查隧道是否发生变化
     */
    private boolean isTunnelChanged(Forward existForward, ForwardUpdateDto updateDto) {
        return !existForward.getTunnelId().equals(updateDto.getTunnelId());
    }

    /**
     * 检查Gost操作是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }


    /**
     * 检查指定的入口端口是否可用（可排除指定的转发ID）
     */
    private boolean isInPortAvailable(Tunnel tunnel, Integer port, Long excludeForwardId) {
        List<Node> inNodes = getEntryNodes(tunnel);
        if (inNodes.isEmpty()) {
            return false;
        }

        // 同一条转发会在每个入口创建服务，因此端口必须同时满足所有入口
        // 的允许范围，并且不能在任何一个入口上被其他转发占用。
        for (Node inNode : inNodes) {
            if (port < inNode.getPortSta() || port > inNode.getPortEnd()) {
                return false;
            }
            if (getAllUsedPortsOnNode(inNode.getId(), excludeForwardId).contains(port)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 为隧道分配一个可用的入口端口（可排除指定的转发ID）
     */
    private Integer allocateInPort(Tunnel tunnel, Long excludeForwardId) {
        List<Node> inNodes = getEntryNodes(tunnel);
        if (inNodes.isEmpty()) {
            return null;
        }

        int rangeStart = inNodes.stream().mapToInt(Node::getPortSta).max().orElse(0);
        int rangeEnd = inNodes.stream().mapToInt(Node::getPortEnd).min().orElse(-1);
        if (rangeStart > rangeEnd) {
            return null;
        }

        Set<Integer> usedPorts = new HashSet<>();
        for (Node inNode : inNodes) {
            usedPorts.addAll(getAllUsedPortsOnNode(inNode.getId(), excludeForwardId));
        }
        return chooseRandomAvailablePort(rangeStart, rangeEnd, usedPorts);
    }

    /**
     * 为隧道分配一个可用的出口端口（可排除指定的转发ID）
     */
    private Integer allocateOutPort(Tunnel tunnel, Long excludeForwardId) {
        return allocatePortForNode(tunnel.getOutNodeId(), excludeForwardId);
    }

    /**
     * 为指定节点分配一个可用端口（通用方法）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 可用端口号，如果没有可用端口则返回null
     */
    private Integer allocatePortForNode(Long nodeId, Long excludeForwardId) {
        // 获取节点信息
        Node node = nodeService.getNodeById(nodeId);
        if (node == null) {
            return null;
        }

        // 获取该节点上所有已被占用的端口（包括作为入口和出口使用的端口）
        Set<Integer> usedPorts = getAllUsedPortsOnNode(nodeId, excludeForwardId);

        return chooseRandomAvailablePort(node.getPortSta(), node.getPortEnd(), usedPorts);
    }

    /**
     * Automatic ports are intentionally non-sequential. A few random probes
     * keep the common case fast; the random-offset fallback still checks every
     * port exactly once so a partially full range cannot produce a false
     * “full” result.
     */
    private Integer chooseRandomAvailablePort(int rangeStart, int rangeEnd, Set<Integer> usedPorts) {
        long rangeSize = (long) rangeEnd - rangeStart + 1;
        long usedInRange = usedPorts.stream()
                .filter(port -> port >= rangeStart && port <= rangeEnd)
                .count();
        if (rangeSize <= 0 || usedInRange >= rangeSize) {
            return null;
        }

        int probes = (int) Math.min(rangeSize, 32);
        for (int attempt = 0; attempt < probes; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(rangeStart, rangeEnd + 1);
            if (!usedPorts.contains(candidate)) {
                return candidate;
            }
        }

        int offset = ThreadLocalRandom.current().nextInt((int) rangeSize);
        for (int step = 0; step < rangeSize; step++) {
            int candidate = rangeStart + (int) ((offset + step) % rangeSize);
            if (!usedPorts.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 获取指定节点上所有已被占用的端口（包括入口和出口端口）
     *
     * @param nodeId           节点ID
     * @param excludeForwardId 要排除的转发ID
     * @return 已占用的端口集合
     */
    private Set<Integer> getAllUsedPortsOnNode(Long nodeId, Long excludeForwardId) {
        return forwardPortReservationService.reservedPorts(nodeId, excludeForwardId);
    }


    /**
     * 构建服务名称，优化后减少重复查询
     */
    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    public void updateForwardA(Forward forward) {
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return;
        }
        UserTunnel userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        NodeInfo nodeInfo = getRequiredNodes(tunnel);
        if (nodeInfo.isHasError()) {
            return;
        }
        Integer limiter;
        if (userTunnel == null) {
            limiter = null;
        } else {
            limiter = userTunnel.getSpeedId();
        }
        updateGostServices(forward, tunnel, limiter, nodeInfo, userTunnel);
    }


    // ========== 内部数据类 ==========

    /**
     * 用户信息封装类
     */
    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;
    }

    /**
     * 用户权限检查结果
     */
    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    /**
     * 端口分配结果
     */
    @Data
    private static class PortAllocation {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer inPort;
        private final Integer outPort;

        private PortAllocation(boolean hasError, String errorMessage, Integer inPort, Integer outPort) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inPort = inPort;
            this.outPort = outPort;
        }

        public static PortAllocation success(Integer inPort, Integer outPort) {
            return new PortAllocation(false, null, inPort, outPort);
        }

        public static PortAllocation error(String errorMessage) {
            return new PortAllocation(true, errorMessage, null, null);
        }
    }

    /**
     * 节点信息封装类
     */
    @Data
    private static class NodeInfo {
        private final boolean hasError;
        private final String errorMessage;
        private final List<Node> inNodes;
        private final Node outNode;

        private NodeInfo(boolean hasError, String errorMessage, List<Node> inNodes, Node outNode) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.inNodes = inNodes;
            this.outNode = outNode;
        }

        public static NodeInfo success(List<Node> inNodes, Node outNode) {
            return new NodeInfo(false, null, inNodes, outNode);
        }

        public static NodeInfo error(String errorMessage) {
            return new NodeInfo(true, errorMessage, Collections.emptyList(), null);
        }
    }

    /**
     * 诊断结果数据类
     */
    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;
    }
}

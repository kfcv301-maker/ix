package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowAccountingResult;
import com.admin.common.dto.FlowBatchAck;
import com.admin.common.dto.FlowBatchResponse;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.task.ForwardPauseTaskService;
import com.admin.common.service.FlowAccountingService;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.Node;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计。
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 计费由 FlowAccountingService 在短事务中完成；暂停节点服务走持久化
 * 重试任务，不会阻塞上报 HTTP 响应或诱发重复计费。
 */
@RestController
@RequestMapping("/flow")
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final int MAX_BATCH_REPORTS = 256;
    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    private FlowAccountingService flowAccountingService;

    @Resource
    private ForwardPauseTaskService forwardPauseTaskService;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    public String config(@RequestBody String rawData,
                         @RequestParam(value = "secret", required = false) String legacySecret,
                         HttpServletRequest request) {
        String secret = getNodeToken(request, legacySecret);
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // GOST agent 的配置上报格式是 {"config": {...}}。旧代码直接把外层
            // 对象解析为 GostConfigDto，导致 services/chains/limiters 始终为 null，
            // 从而节点重装后无法识别并补回丢失的转发配置。
            JSONObject configPayload = JSON.parseObject(decryptedData);
            JSONObject actualConfig = configPayload.getJSONObject("config");
            if (actualConfig != null) {
                configPayload = actualConfig;
            }
            GostConfigDto gostConfigDto = configPayload.toJavaObject(GostConfigDto.class);
            try {
                checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);
            } catch (RuntimeException exception) {
                // A bounded sync worker may be busy. The next inventory report
                // and the periodic node reporter will retry without blocking it.
                log.warn("节点 {} 配置同步已排满，稍后重试", node.getId());
            }
            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    public ResponseEntity<String> uploadFlowData(@RequestBody String rawData,
                                                  @RequestParam(value = "secret", required = false) String legacySecret,
                                                  HttpServletRequest request) {
        String secret = getNodeToken(request, legacySecret);
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) {
            return ResponseEntity.ok(SUCCESS_RESPONSE);
        }

        try {
            String decryptedData = decryptIfNeeded(rawData, secret);
            JSONObject payload = JSON.parseObject(decryptedData);
            JSONArray batch = payload == null ? null : payload.getJSONArray("items");
            if (batch != null) {
                return uploadBatch(node, batch);
            }
            return uploadSingle(node, payload == null ? null : payload.toJavaObject(FlowDto.class));
        } catch (Exception exception) {
            log.warn("处理节点 {} 流量上报失败: {}", node.getId(), exception.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("retry_later");
        }
    }

    private ResponseEntity<String> uploadSingle(Node node, FlowDto flowData) {
        if (flowData != null && Objects.equals(flowData.getN(), "web_api")) {
            return ResponseEntity.ok(SUCCESS_RESPONSE);
        }
        FlowAccountingResult result = flowAccountingService.account(node, flowData);
        if (result.isAccepted()) {
            if (result.isPauseWorkQueued()) {
                forwardPauseTaskService.dispatchPendingTasks();
            }
            return ResponseEntity.ok(SUCCESS_RESPONSE);
        }
        if (result.isDuplicate()) {
            return ResponseEntity.ok(SUCCESS_RESPONSE);
        }
        if (result.isUpgradeRequired()) {
            log.warn("节点 {} 使用了不支持幂等计费的旧流量上报协议", node.getId());
            return ResponseEntity.status(HttpStatus.UPGRADE_REQUIRED).body("upgrade_required");
        }
        log.warn("拒绝节点 {} 的流量上报: {}", node.getId(), result.getReason());
        // Rejected reports are acknowledged so a malformed or unauthorized
        // service cannot keep an agent retrying forever.
        return ResponseEntity.ok(SUCCESS_RESPONSE);
    }

    /**
     * A batch still accounts each service independently, preserving its own
     * transaction and idempotency cursor. The response acknowledges exactly
     * the reports the Agent may retire, so a transient failure retries only
     * that service rather than the entire batch.
     */
    private ResponseEntity<String> uploadBatch(Node node, JSONArray rawItems) {
        FlowBatchResponse response = new FlowBatchResponse();
        if (rawItems.size() > MAX_BATCH_REPORTS) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(JSON.toJSONString(response));
        }

        boolean pauseWorkQueued = false;
        for (Object rawItem : rawItems) {
            FlowDto report;
            try {
                report = JSONObject.parseObject(JSON.toJSONString(rawItem), FlowDto.class);
            } catch (Exception exception) {
                log.warn("节点 {} 的批量流量项格式非法: {}", node.getId(), exception.getMessage());
                continue;
            }
            if (report == null) continue;
            if (Objects.equals(report.getN(), "web_api")) {
                response.getAcknowledged().add(FlowBatchAck.from(report));
                continue;
            }
            try {
                FlowAccountingResult result = flowAccountingService.account(node, report);
                if (result.isAccepted()) {
                    pauseWorkQueued |= result.isPauseWorkQueued();
                    response.getAcknowledged().add(FlowBatchAck.from(report));
                } else if (result.isDuplicate()) {
                    response.getAcknowledged().add(FlowBatchAck.from(report));
                } else if (result.isUpgradeRequired()) {
                    log.warn("节点 {} 的批量流量项使用了旧上报协议", node.getId());
                } else {
                    log.warn("拒绝节点 {} 的批量流量上报: {}", node.getId(), result.getReason());
                    response.getAcknowledged().add(FlowBatchAck.from(report));
                }
            } catch (Exception exception) {
                // Omit only this acknowledgement. The Agent keeps its exact
                // sequence and retries it in a later batch.
                log.warn("处理节点 {} 的批量流量项失败: {}", node.getId(), exception.getMessage());
            }
        }
        if (pauseWorkQueued) {
            forwardPauseTaskService.dispatchPendingTasks();
        }
        return ResponseEntity.ok(JSON.toJSONString(response));
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    private String getNodeToken(HttpServletRequest request, String legacySecret) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return legacySecret;
    }

}

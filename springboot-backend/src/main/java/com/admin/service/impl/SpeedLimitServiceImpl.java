package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.dto.SpeedLimitDto;
import com.admin.common.dto.SpeedLimitUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.SpeedLimit;
import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.SpeedLimitMapper;
import com.admin.service.SpeedLimitService;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * <p>
 * 限速规则服务实现类
 * 提供限速规则的增删改查功能，包括与Gost服务的集成
 * 支持限速器的创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-04
 */
@Service
public class SpeedLimitServiceImpl extends ServiceImpl<SpeedLimitMapper, SpeedLimit> implements SpeedLimitService {

    // ========== 常量定义 ==========
    
    /** Gost操作成功响应消息 */
    private static final String GOST_SUCCESS_MSG = "OK";
    
    /** Gost未找到资源响应消息 */
    private static final String GOST_NOT_FOUND_MSG = "not found";
    
    /** 限速规则状态 */
    private static final int SPEED_LIMIT_ACTIVE_STATUS = 1;
    private static final int SPEED_LIMIT_INACTIVE_STATUS = 0;
    
    /** 速度转换比率：比特到字节 */
    private static final double BITS_TO_BYTES_RATIO = 8.0;
    
    /** 成功响应消息 */
    private static final String SUCCESS_UPDATE_MSG = "限速规则更新成功";
    private static final String SUCCESS_DELETE_MSG = "限速规则删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "限速规则创建失败";
    private static final String ERROR_UPDATE_MSG = "限速规则更新失败";
    private static final String ERROR_DELETE_MSG = "限速规则删除失败";
    private static final String ERROR_SPEED_LIMIT_NOT_FOUND = "限速规则不存在";
    private static final String ERROR_TUNNEL_NOT_FOUND = "指定的隧道不存在";
    private static final String ERROR_TUNNEL_NOT_EXISTS = "隧道不存在";
    private static final String ERROR_TUNNEL_NAME_MISMATCH = "隧道名称与隧道ID不匹配";
    private static final String ERROR_SPEED_LIMIT_IN_USE = "该限速规则还有用户在使用 请先取消分配";

    // ========== 依赖注入 ==========
    
    @Autowired
    @Lazy
    private TunnelService tunnelService;

    @Autowired
    private UserTunnelService userTunnelService;

    @Autowired
    @Lazy
    private SpeedLimitService speedLimitService;

    @Autowired
    private TunnelIngressNodeResolver ingressNodeResolver;

    // ========== 公共接口实现 ==========

    /**
     * 创建限速规则
     * 
     * @param speedLimitDto 限速规则创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createSpeedLimit(SpeedLimitDto speedLimitDto) {
        // 1. 验证隧道
        TunnelValidationResult tunnelValidation = validateTunnelWithResult(speedLimitDto.getTunnelId(), speedLimitDto.getTunnelName());
        if (tunnelValidation.isHasError()) {
            return R.err(tunnelValidation.getErrorMessage());
        }

        // 2. 创建限速规则实体
        SpeedLimit speedLimit = createSpeedLimitEntity(speedLimitDto);
        if (!this.save(speedLimit)) {
            return R.err(ERROR_CREATE_MSG);
        }

        // 3. 调用Gost API添加限速器
        R gostResult = addGostLimiter(speedLimit, tunnelValidation.getTunnel());
        if (gostResult.getCode() != 0) {
            handleGostOperationFailure(speedLimit);
            this.removeById(speedLimit.getId());
            return gostResult;
        }
        
        return R.ok();
    }

    /**
     * 获取所有限速规则
     * 
     * @return 包含所有限速规则的响应对象
     */
    @Override
    public R getAllSpeedLimits() {
        List<SpeedLimit> speedLimits = this.list();
        return R.ok(speedLimits);
    }

    /**
     * 更新限速规则
     * 
     * @param speedLimitUpdateDto 限速规则更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateSpeedLimit(SpeedLimitUpdateDto speedLimitUpdateDto) {
        // 1. 验证限速规则是否存在
        SpeedLimit speedLimit = this.getById(speedLimitUpdateDto.getId());
        if (speedLimit == null) {
            return R.err(ERROR_SPEED_LIMIT_NOT_FOUND);
        }

        // 2. 验证隧道
        TunnelValidationResult tunnelValidation = validateTunnelWithResult(speedLimitUpdateDto.getTunnelId(), speedLimitUpdateDto.getTunnelName());
        if (tunnelValidation.isHasError()) {
            return R.err(tunnelValidation.getErrorMessage());
        }

        // 3. 更新限速规则数据
        updateSpeedLimitEntity(speedLimitUpdateDto, speedLimit);

        // 4. 调用Gost API更新限速器
        R gostResult = updateGostLimiter(speedLimit, tunnelValidation.getTunnel());
        if (gostResult.getCode() != 0) {
            return gostResult;
        }

        // 5. 保存更新
        boolean result = this.updateById(speedLimit);
        return result ? R.ok(SUCCESS_UPDATE_MSG) : R.err(ERROR_UPDATE_MSG);
    }

    /**
     * 删除限速规则
     * 删除前会检查是否有用户正在使用该限速规则
     * 
     * @param id 限速规则ID
     * @return 删除结果响应
     */
    @Override
    public R deleteSpeedLimit(Long id) {
        // 1. 验证限速规则是否存在
        SpeedLimit speedLimit = this.getById(id);
        if (speedLimit == null) {
            return R.err(ERROR_SPEED_LIMIT_NOT_FOUND);
        }
        
        // 2. 检查使用情况
        R usageCheckResult = checkSpeedLimitUsage(id);
        if (usageCheckResult.getCode() != 0) {
            return usageCheckResult;
        }
        
        // 3. 获取隧道信息
        Tunnel tunnel = tunnelService.getById(speedLimit.getTunnelId());
        if (tunnel == null) {
            this.removeById(id);
            return R.ok();
        }

        // 4. 所有入口都确认删除后才移除数据库规则，避免孤立的限速器。
        R gostResult = deleteGostLimiter(id, tunnel);
        if (gostResult.getCode() != 0) {
            return gostResult;
        }

        // 5. 删除限速规则
        boolean result = this.removeById(id);
        return result ? R.ok(SUCCESS_DELETE_MSG) : R.err(ERROR_DELETE_MSG);
    }

    @Override
    public R ensureLimiterOnNode(Long speedLimitId, Long nodeId) {
        if (speedLimitId == null || nodeId == null) {
            return R.err("限速规则或入口节点不能为空");
        }
        SpeedLimit speedLimit = this.getById(speedLimitId);
        if (speedLimit == null) {
            return R.err(ERROR_SPEED_LIMIT_NOT_FOUND);
        }
        Tunnel tunnel = tunnelService.getById(speedLimit.getTunnelId());
        if (tunnel == null || !ingressNodeResolver.isIngressNode(tunnel, nodeId)) {
            return R.err("限速规则不属于该入口节点");
        }
        return updateGostLimiterOnNode(speedLimit, nodeId);
    }

    // ========== 私有辅助方法 ==========

    /**
     * 验证隧道是否存在且名称匹配（返回详细结果）
     * 
     * @param tunnelId 隧道ID
     * @param tunnelName 隧道名称
     * @return 隧道验证结果
     */
    private TunnelValidationResult validateTunnelWithResult(Long tunnelId, String tunnelName) {
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            return TunnelValidationResult.error(ERROR_TUNNEL_NOT_FOUND);
        }
        
        if (!tunnel.getName().equals(tunnelName)) {
            return TunnelValidationResult.error(ERROR_TUNNEL_NAME_MISMATCH);
        }
        
        return TunnelValidationResult.success(tunnel);
    }

    /**
     * 验证隧道是否存在且名称匹配（兼容原有方法）
     * 
     * @param tunnelId 隧道ID
     * @param tunnelName 隧道名称
     * @return 验证结果响应
     */
    private R validateTunnel(Long tunnelId, String tunnelName) {
        TunnelValidationResult result = validateTunnelWithResult(tunnelId, tunnelName);
        return result.isHasError() ? R.err(result.getErrorMessage()) : R.ok(result.getTunnel());
    }

    /**
     * 创建限速规则实体对象
     * 
     * @param speedLimitDto 限速规则创建DTO
     * @return 构建完成的限速规则对象
     */
    private SpeedLimit createSpeedLimitEntity(SpeedLimitDto speedLimitDto) {
        SpeedLimit speedLimit = new SpeedLimit();
        BeanUtils.copyProperties(speedLimitDto, speedLimit);
        
        // 设置默认属性
        long currentTime = System.currentTimeMillis();
        speedLimit.setCreatedTime(currentTime);
        speedLimit.setUpdatedTime(currentTime);
        speedLimit.setStatus(SPEED_LIMIT_ACTIVE_STATUS);
        
        return speedLimit;
    }

    /**
     * 更新限速规则实体对象
     * 
     * @param speedLimitUpdateDto 限速规则更新DTO
     * @param speedLimit 待更新的限速规则对象
     */
    private void updateSpeedLimitEntity(SpeedLimitUpdateDto speedLimitUpdateDto, SpeedLimit speedLimit) {
        BeanUtils.copyProperties(speedLimitUpdateDto, speedLimit);
        speedLimit.setUpdatedTime(System.currentTimeMillis());
    }

    /**
     * 检查限速规则使用情况
     * 
     * @param speedLimitId 限速规则ID
     * @return 检查结果响应
     */
    private R checkSpeedLimitUsage(Long speedLimitId) {
        int userCount = userTunnelService.count(new QueryWrapper<UserTunnel>().eq("speed_id", speedLimitId));
        if (userCount != 0) {
            return R.err(ERROR_SPEED_LIMIT_IN_USE);
        }
        return R.ok();
    }

    /**
     * 添加Gost限速器
     * 
     * @param speedLimit 限速规则对象
     * @param tunnel 隧道对象
     * @return 操作结果响应
     */
    private R addGostLimiter(SpeedLimit speedLimit, Tunnel tunnel) {
        String speedInMBps = convertBitsToMBps(speedLimit.getSpeed());
        Set<Long> ingressNodeIds = ingressNodeResolver.resolveNodeIds(tunnel);
        if (ingressNodeIds.isEmpty()) return R.err("隧道没有入口节点");
        for (Long nodeId : ingressNodeIds) {
            GostDto gostResult = GostUtil.AddLimiters(nodeId, speedLimit.getId(), speedInMBps);
            if (!isGostOperationSuccess(gostResult)) {
                return R.err("入口节点 " + nodeId + " 创建限速器失败：" + gostMessage(gostResult));
            }
        }
        return R.ok();
    }

    /**
     * 更新Gost限速器
     * 
     * @param speedLimit 限速规则对象
     * @param tunnel 隧道对象
     * @return 操作结果响应
     */
    private R updateGostLimiter(SpeedLimit speedLimit, Tunnel tunnel) {
        Set<Long> ingressNodeIds = ingressNodeResolver.resolveNodeIds(tunnel);
        if (ingressNodeIds.isEmpty()) return R.err("隧道没有入口节点");
        for (Long nodeId : ingressNodeIds) {
            R result = updateGostLimiterOnNode(speedLimit, nodeId);
            if (result.getCode() != 0) return result;
        }
        return R.ok();
    }

    /**
     * 删除Gost限速器
     * 
     * @param speedLimitId 限速规则ID
     * @param tunnel 隧道对象
     * @return 操作结果响应
     */
    private R deleteGostLimiter(Long speedLimitId, Tunnel tunnel) {
        Set<Long> ingressNodeIds = ingressNodeResolver.resolveNodeIds(tunnel);
        if (ingressNodeIds.isEmpty()) return R.err("隧道没有入口节点");
        for (Long nodeId : ingressNodeIds) {
            GostDto gostResult = GostUtil.DeleteLimiters(nodeId, speedLimitId);
            if (!isGostOperationSuccess(gostResult)) {
                return R.err("入口节点 " + nodeId + " 删除限速器失败：" + gostMessage(gostResult));
            }
        }
        return R.ok();
    }

    private R updateGostLimiterOnNode(SpeedLimit speedLimit, Long nodeId) {
        String speedInMBps = convertBitsToMBps(speedLimit.getSpeed());
        GostDto gostResult = GostUtil.UpdateLimiters(nodeId, speedLimit.getId(), speedInMBps);
        if (gostResult != null && gostResult.getMsg() != null && gostResult.getMsg().contains(GOST_NOT_FOUND_MSG)) {
            gostResult = GostUtil.AddLimiters(nodeId, speedLimit.getId(), speedInMBps);
        }
        return isGostOperationSuccess(gostResult) ? R.ok()
                : R.err("入口节点 " + nodeId + " 同步限速器失败：" + gostMessage(gostResult));
    }

    /**
     * 处理Gost操作失败的情况
     * 
     * @param speedLimit 限速规则对象
     */
    private void handleGostOperationFailure(SpeedLimit speedLimit) {
        speedLimit.setStatus(SPEED_LIMIT_INACTIVE_STATUS);
        speedLimitService.updateById(speedLimit);
    }

    /**
     * 将比特率转换为兆字节每秒
     * 
     * @param speedInBits 比特率速度
     * @return 兆字节每秒字符串
     */
    private String convertBitsToMBps(Integer speedInBits) {
        double mbs = speedInBits / BITS_TO_BYTES_RATIO;
        BigDecimal bd = new BigDecimal(mbs).setScale(1, RoundingMode.HALF_UP);
        return bd.doubleValue() + "";
    }

    /**
     * 检查Gost操作是否成功
     * 
     * @param gostResult Gost操作结果
     * @return 是否成功
     */
    private boolean isGostOperationSuccess(GostDto gostResult) {
        return gostResult != null && Objects.equals(gostResult.getMsg(), GOST_SUCCESS_MSG);
    }

    private String gostMessage(GostDto gostResult) {
        return gostResult == null || gostResult.getMsg() == null ? "无响应" : gostResult.getMsg();
    }

    // ========== 内部数据类 ==========

    /**
     * 隧道验证结果封装类
     */
    @Data
    private static class TunnelValidationResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Tunnel tunnel;

        private TunnelValidationResult(boolean hasError, String errorMessage, Tunnel tunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.tunnel = tunnel;
        }

        public static TunnelValidationResult success(Tunnel tunnel) {
            return new TunnelValidationResult(false, null, tunnel);
        }

        public static TunnelValidationResult error(String errorMessage) {
            return new TunnelValidationResult(true, errorMessage, null);
        }
    }
}

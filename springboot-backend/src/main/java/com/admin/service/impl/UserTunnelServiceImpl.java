package com.admin.service.impl;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelQueryDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.dto.UserTunnelWithDetailDto;
import com.admin.common.lang.R;
import com.admin.common.task.ForwardPauseTaskService;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelService;
import com.admin.service.UserTunnelService;
import com.admin.service.ForwardService;
import com.admin.service.TunnelEntryDomainService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * <p>
 * 用户隧道权限服务实现类
 * 提供用户隧道权限的分配、查询、更新和删除功能
 * 支持流量限制、数量限制、过期时间和限速规则的管理
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class UserTunnelServiceImpl extends ServiceImpl<UserTunnelMapper, UserTunnel> implements UserTunnelService {

    // ========== 常量定义 ==========
    
    /** 成功响应消息 */
    private static final String SUCCESS_ASSIGN_MSG = "用户隧道权限分配成功";
    private static final String SUCCESS_REMOVE_MSG = "用户隧道权限删除成功";
    private static final String SUCCESS_UPDATE_FLOW_MSG = "用户隧道流量限制更新成功";
    private static final String SUCCESS_UPDATE_MSG = "用户隧道权限更新成功";
    
    /** 错误响应消息 */
    private static final String ERROR_ASSIGN_FAILED = "用户隧道权限分配失败";
    private static final String ERROR_PERMISSION_EXISTS = "该用户已拥有此隧道权限";
    private static final String ERROR_PERMISSION_NOT_FOUND = "未找到对应的用户隧道权限记录";
    private static final String ERROR_USER_TUNNEL_NOT_EXISTS = "用户隧道权限不存在";
    private static final String ERROR_NOT_EXISTS = "不存在";
    private static final String ERROR_UPDATE_FAILED = "用户隧道权限更新失败";

    // ========== 依赖注入 ==========
    
    @Autowired
    @Lazy
    private ForwardService forwardService;
    
    @Autowired
    @Lazy
    private TunnelService tunnelService;
    
    @Autowired
    private TunnelEntryDomainService tunnelEntryDomainService;

    @Autowired
    private TunnelIngressNodeResolver ingressNodeResolver;

    // The worker itself resolves UserTunnelService while processing a task.
    // Lazy injection keeps that graph acyclic at application startup.
    @Autowired
    @Lazy
    private ForwardPauseTaskService forwardPauseTaskService;

    // ========== 公共接口实现 ==========

    /**
     * 分配用户隧道权限
     * 检查权限是否已存在，避免重复分配
     * 
     * @param userTunnelDto 用户隧道权限分配数据传输对象
     * @return 分配结果响应
     */
    @Override
    public R assignUserTunnel(UserTunnelDto userTunnelDto) {
        Tunnel tunnel = tunnelService.getById(userTunnelDto.getTunnelId());
        if (tunnel == null) return R.err("隧道不存在");
        if (tunnel.getOwnerUserId() != null
                && !tunnel.getOwnerUserId().equals(userTunnelDto.getUserId().longValue())) {
            return R.err(403, "用户自建隧道不能授权给其他用户");
        }
        // 1. 检查权限是否已存在
        if (isUserTunnelPermissionExists(userTunnelDto.getUserId(), userTunnelDto.getTunnelId())) {
            return R.err(ERROR_PERMISSION_EXISTS);
        }
        
        // 2. 创建用户隧道权限实体并验证其可见入口地址。
        // This only changes panel display/authorization data; forwarding and
        // node-side configuration are deliberately not touched.
        UserTunnel userTunnel = buildUserTunnelEntity(userTunnelDto);
        R entryAddressResult = applyEntryAddressAssignment(
                userTunnel,
                userTunnelDto.getTunnelId(),
                userTunnelDto.getEntryAddressMode(),
                userTunnelDto.getEntryDomainId());
        if (entryAddressResult.getCode() != 0) {
            return entryAddressResult;
        }
        // 设置默认状态为启用
        userTunnel.setStatus(1);
        boolean success = this.save(userTunnel);

        if (success) {
            WebSocketServer.closeUserSessions(userTunnel.getUserId().longValue());
            return R.ok(SUCCESS_ASSIGN_MSG);
        }
        
        return R.err(ERROR_ASSIGN_FAILED);
    }

    /**
     * 获取用户隧道权限列表
     * 通过连表查询获取用户隧道权限及隧道详细信息
     * 
     * @param queryDto 用户隧道权限查询数据传输对象
     * @return 用户隧道权限详情列表响应
     */
    @Override
    public R getUserTunnelList(UserTunnelQueryDto queryDto) {
        List<UserTunnelWithDetailDto> userTunnelDetails = getUserTunnelDetailsFromDatabase(queryDto.getUserId());
        return R.ok(userTunnelDetails);
    }

    /**
     * 删除用户隧道权限
     * 
     * @param id 用户隧道权限ID
     * @return 删除结果响应
     */
    @Override
    public R removeUserTunnel(Integer id) {
        // 1. 获取用户隧道权限信息
        UserTunnel userTunnel = this.getById(id);
        if (userTunnel == null) {
            return R.err(ERROR_PERMISSION_NOT_FOUND);
        }

        // Do not let a permission deletion fall back to direct database
        // removal of forwards. Each forward must first enter the durable
        // per-node delete workflow and remain visible until every Agent ACKs.
        long forwardCount = forwardService.count(new QueryWrapper<Forward>()
                .eq("user_id", userTunnel.getUserId()).eq("tunnel_id", userTunnel.getTunnelId()));
        if (forwardCount > 0) {
            return R.err("该权限下仍有 " + forwardCount + " 条转发，请先在转发管理中删除并等待节点清理完成");
        }

        // 删除用户隧道权限记录
        boolean success = this.removeById(id);
        if (success) {
            WebSocketServer.closeUserSessions(userTunnel.getUserId().longValue());
            return R.ok(SUCCESS_REMOVE_MSG);
        }
        return R.err(ERROR_PERMISSION_NOT_FOUND);
    }


    /**
     * 更新用户隧道权限
     * 支持更新流量限制、数量限制、流量重置时间、过期时间和限速规则
     * 
     * @param updateDto 用户隧道权限更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateUserTunnel(UserTunnelUpdateDto updateDto) {
        // 1. 验证用户隧道权限是否存在
        UserTunnel existingUserTunnel = this.getById(updateDto.getId());
        if (existingUserTunnel == null) {
            return R.err(ERROR_USER_TUNNEL_NOT_EXISTS);
        }

        // Older API clients may omit these two fields; in that case retain
        // their existing entry selection instead of silently changing it.
        if (StringUtils.isNotBlank(updateDto.getEntryAddressMode())) {
            R entryAddressResult = applyEntryAddressAssignment(
                    existingUserTunnel,
                    existingUserTunnel.getTunnelId(),
                    updateDto.getEntryAddressMode(),
                    updateDto.getEntryDomainId());
            if (entryAddressResult.getCode() != 0) {
                return entryAddressResult;
            }
        }
        
        // 2. 检查是否更新了限速规则
        boolean speedChanged = hasSpeedChanged(existingUserTunnel.getSpeedId(), updateDto.getSpeedId());
        
        // 3. 更新用户隧道权限属性
        updateUserTunnelProperties(existingUserTunnel, updateDto);
        
        // 4. Update only entitlement fields. Writing the loaded entity back
        // would overwrite in_flow/out_flow that a concurrent node report just
        // atomically incremented.
        UpdateWrapper<UserTunnel> update = new UpdateWrapper<>();
        update.eq("id", existingUserTunnel.getId())
                .set("flow", existingUserTunnel.getFlow())
                .set("num", existingUserTunnel.getNum())
                .set("flow_reset_time", existingUserTunnel.getFlowResetTime())
                .set("exp_time", existingUserTunnel.getExpTime())
                .set("status", existingUserTunnel.getStatus())
                .set("speed_id", existingUserTunnel.getSpeedId())
                .set("entry_address_mode", existingUserTunnel.getEntryAddressMode())
                .set("entry_domain_id", existingUserTunnel.getEntryDomainId());
        boolean success = this.update(null, update);
        
        if (success) {
            WebSocketServer.closeUserSessions(existingUserTunnel.getUserId().longValue());
            forwardPauseTaskService.enqueueForCurrentLimits(existingUserTunnel.getUserId().longValue(),
                    existingUserTunnel.getId().longValue());
            // 6. 如果限速规则发生变化，更新该用户隧道下的所有转发
            if (speedChanged) {
                updateUserTunnelForwardsSpeed(existingUserTunnel.getUserId(), existingUserTunnel.getTunnelId(), updateDto.getSpeedId());
            }
            
            return R.ok(SUCCESS_UPDATE_MSG);
        }
        
        return R.err(ERROR_UPDATE_FAILED);
    }

    // ========== 私有辅助方法 ==========

    /**
     * 检查用户隧道权限是否已存在
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @return 权限是否已存在
     */
    private boolean isUserTunnelPermissionExists(Integer userId, Integer tunnelId) {
        QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
        UserTunnel existingUserTunnel = this.getOne(queryWrapper);
        return existingUserTunnel != null;
    }

    /**
     * 构建用户隧道权限实体对象
     * 
     * @param userTunnelDto 用户隧道权限DTO
     * @return 构建完成的用户隧道权限对象
     */
    private UserTunnel buildUserTunnelEntity(UserTunnelDto userTunnelDto) {
        UserTunnel userTunnel = new UserTunnel();
        BeanUtils.copyProperties(userTunnelDto, userTunnel);
        return userTunnel;
    }

    /**
     * Normalizes the three allowed display choices and verifies that a custom
     * or default domain belongs to the selected tunnel before persisting it.
     */
    private R applyEntryAddressAssignment(UserTunnel userTunnel, Integer tunnelId,
                                          String requestedMode, Long requestedDomainId) {
        String mode = StringUtils.isBlank(requestedMode)
                ? TunnelEntryDomainService.ENTRY_ADDRESS_MODE_NONE
                : requestedMode.trim().toUpperCase(Locale.ROOT);
        R validation = tunnelEntryDomainService.validateUserEntryAssignment(tunnelId, mode, requestedDomainId);
        if (validation.getCode() != 0) {
            return validation;
        }
        userTunnel.setEntryAddressMode(mode);
        userTunnel.setEntryDomainId(TunnelEntryDomainService.ENTRY_ADDRESS_MODE_CUSTOM.equals(mode)
                ? requestedDomainId : null);
        return R.ok();
    }

    /**
     * 从数据库获取用户隧道权限详情
     * 
     * @param userId 用户ID
     * @return 用户隧道权限详情列表
     */
    private List<UserTunnelWithDetailDto> getUserTunnelDetailsFromDatabase(Integer userId) {
        return this.baseMapper.getUserTunnelWithDetails(userId);
    }

    /**
     * 更新用户隧道权限属性
     * 
     * @param existingUserTunnel 现有的用户隧道权限对象
     * @param updateDto 更新数据传输对象
     */
    private void updateUserTunnelProperties(UserTunnel existingUserTunnel, UserTunnelUpdateDto updateDto) {
        // 更新基本属性
        existingUserTunnel.setFlow(updateDto.getFlow());
        existingUserTunnel.setNum(updateDto.getNum());
        
        // 更新可选属性（仅在非空时更新）
        updateOptionalProperty(existingUserTunnel::setFlowResetTime, updateDto.getFlowResetTime());
        updateOptionalProperty(existingUserTunnel::setExpTime, updateDto.getExpTime());
        updateOptionalProperty(existingUserTunnel::setStatus, updateDto.getStatus());
        
        // 更新限速规则ID（允许设置为null，表示不限速）
        existingUserTunnel.setSpeedId(updateDto.getSpeedId());
    }

    /**
     * 更新可选属性（仅在值非空时更新）
     * 
     * @param setter 属性设置方法
     * @param value 属性值
     * @param <T> 属性类型
     */
    private <T> void updateOptionalProperty(java.util.function.Consumer<T> setter, T value) {
        if (value != null) {
            setter.accept(value);
        }
    }
    

    
    /**
     * 根据用户ID和隧道ID获取用户隧道权限
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @return 用户隧道权限对象
     */
    private UserTunnel getUserTunnelByUserAndTunnel(Integer userId, Integer tunnelId) {
        try {
            QueryWrapper<UserTunnel> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
            return this.getOne(queryWrapper);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 构建服务名称
     * 
     * @param forwardId 转发ID
     * @param userId 用户ID
     * @param userTunnelId 用户隧道ID
     * @return 服务名称
     */
    private String buildServiceName(Long forwardId, Long userId, Integer userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    /**
     * 检查用户隧道是否启用且有到期时间
     * 
     * @param userTunnel 用户隧道对象
     * @return 是否启用且有到期时间
     */
    private boolean isEnabledAndHasExpTime(UserTunnel userTunnel) {
        return userTunnel.getStatus() != null && userTunnel.getStatus() == 1 
                && userTunnel.getExpTime() != null;
    }
    
    /**
     * 检查限速规则是否发生变化
     * 
     * @param oldSpeedId 原始限速规则ID
     * @param newSpeedId 新的限速规则ID
     * @return 限速规则是否发生变化
     */
    private boolean hasSpeedChanged(Integer oldSpeedId, Integer newSpeedId) {
        if (oldSpeedId == null && newSpeedId == null) {
            return false;
        }
        if (oldSpeedId == null || newSpeedId == null) {
            return true;
        }
        return !oldSpeedId.equals(newSpeedId);
    }
    
    /**
     * 更新用户隧道下所有转发的限速规则
     * 管理员操作，不需要权限检查，直接查出该用户在该隧道下的所有转发并应用新的限速
     * 
     * @param userId 用户ID
     * @param tunnelId 隧道ID
     * @param speedId 新的限速规则ID
     */
    private void updateUserTunnelForwardsSpeed(Integer userId, Integer tunnelId, Integer speedId) {
        // 1. 查询该用户在该隧道下的所有转发
        QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("tunnel_id", tunnelId);
        List<Forward> userTunnelForwards = forwardService.list(queryWrapper);

        if (userTunnelForwards.isEmpty()) {
            return;
        }

        // 2. 获取隧道信息
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel == null) {
            return;
        }

        // 3. 获取用户隧道权限信息
        UserTunnel userTunnel = getUserTunnelByUserAndTunnel(userId, tunnelId);
        if (userTunnel == null) {
            return;
        }

        // 4. Every ingress publishes its own copy of this service. Updating
        // only tunnel.in_node_id leaves secondary entries on their old limiter.
        Set<Long> ingressNodeIds = ingressNodeResolver.resolveNodeIds(tunnel);
        if (ingressNodeIds.isEmpty()) {
            return;
        }

        // 5. 批量更新该用户在该隧道下所有转发的限速配置。
        for (Forward forward : userTunnelForwards) {
            String serviceName = buildServiceName(forward.getId(), Long.valueOf(userId), userTunnel.getId());

            String interfaceName = null;
            // 创建主服务
            if (tunnel.getType() != 2) { // 不是隧道转发服务才会存在网络接口
                interfaceName = forward.getInterfaceName();
            }

            // 6. Update every ingress; a later node inventory report repairs a
            // missing limiter before restoring a service on that node.
            for (Long nodeId : ingressNodeIds) {
                GostUtil.UpdateService(nodeId, serviceName, forward.getInPort(), speedId,
                        forward.getRemoteAddr(), tunnel.getType(), tunnel, forward.getStrategy(), interfaceName);
            }
        }
    }
}

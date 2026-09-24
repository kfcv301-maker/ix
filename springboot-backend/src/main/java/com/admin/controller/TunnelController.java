package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.TunnelDto;
import com.admin.common.dto.TunnelEntryDomainDto;
import com.admin.common.dto.TunnelEntryDomainIdDto;
import com.admin.common.dto.TunnelEntryDomainQueryDto;
import com.admin.common.dto.TunnelUpdateDto;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelQueryDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.TunnelService;
import com.admin.service.TunnelEntryDomainService;
import com.admin.service.UserTunnelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 * 隧道前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/tunnel")
public class TunnelController extends BaseController {

    @Autowired
    private TunnelService tunnelService;
    
    @Autowired
    private UserTunnelService userTunnelService;

    @Autowired
    private TunnelEntryDomainService tunnelEntryDomainService;

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody TunnelDto tunnelDto) {
        return tunnelService.createTunnel(tunnelDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R readAll() {
        return tunnelService.getAllTunnels();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody TunnelUpdateDto tunnelUpdateDto) {
        return tunnelService.updateTunnel(tunnelUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return tunnelService.deleteTunnel(id);
    }

    // ============ 隧道解析域名池（仅面板显示与分配，不修改 DDNS） ============

    @LogAnnotation
    @RequireRole
    @PostMapping("/domain/list")
    public R getEntryDomains(@Validated @RequestBody TunnelEntryDomainQueryDto queryDto) {
        return tunnelEntryDomainService.getTunnelEntryDomains(queryDto.getTunnelId());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/domain/create")
    public R createEntryDomain(@Validated @RequestBody TunnelEntryDomainDto dto) {
        return tunnelEntryDomainService.createTunnelEntryDomain(dto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/domain/set-default")
    public R setDefaultEntryDomain(@Validated @RequestBody TunnelEntryDomainIdDto dto) {
        return tunnelEntryDomainService.setDefaultTunnelEntryDomain(dto.getId());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/domain/delete")
    public R deleteEntryDomain(@Validated @RequestBody TunnelEntryDomainIdDto dto) {
        return tunnelEntryDomainService.deleteTunnelEntryDomain(dto.getId());
    }

    // ============ 用户隧道权限管理相关方法 ============
    
    /**
     * 分配用户隧道权限
     * @param userTunnelDto 用户隧道权限数据
     * @return 操作结果
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/user/assign")
    public R assignUserTunnel(@Validated @RequestBody UserTunnelDto userTunnelDto) {
        return userTunnelService.assignUserTunnel(userTunnelDto);
    }
    
    /**
     * 查询用户隧道权限列表
     * @param queryDto 查询条件
     * @return 用户隧道权限列表
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/user/list")
    public R getUserTunnelList(@RequestBody @Validated UserTunnelQueryDto queryDto) {
        return userTunnelService.getUserTunnelList(queryDto);
    }
    
    /**
     * 删除用户隧道权限
     * @param params 包含userId和tunnelId的参数
     * @return 操作结果
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/user/remove")
    public R removeUserTunnel(@RequestBody Map<String, Object> params) {
        Integer id = Integer.valueOf(params.get("id").toString());
        return userTunnelService.removeUserTunnel(id);
    }

    
    /**
     * 更新用户隧道权限（包含流量、流量重置时间、到期时间）
     * @param updateDto 更新数据
     * @return 操作结果
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/user/update")
    public R updateUserTunnel(@Validated @RequestBody UserTunnelUpdateDto updateDto) {
        return userTunnelService.updateUserTunnel(updateDto);
    }


    @LogAnnotation
    @PostMapping("/user/tunnel")
    public R userTunnel() {
        return tunnelService.userTunnel();
    }

    /**
     * 隧道诊断功能
     * @param params 包含tunnelId的参数
     * @return 诊断结果
     */
    @LogAnnotation
    @RequireRole
    @PostMapping("/diagnose")
    public R diagnoseTunnel(@RequestBody Map<String, Object> params) {
        Long tunnelId = Long.valueOf(params.get("tunnelId").toString());
        return tunnelService.diagnoseTunnel(tunnelId);
    }

    /**
     * 一键检测隧道下每一条转发的完整 TCP 链路。
     */
    @LogAnnotation
    @PostMapping("/diagnose-forwards")
    public R diagnoseTunnelForwards(@RequestBody Map<String, Object> params) {
        Long tunnelId = Long.valueOf(params.get("tunnelId").toString());
        return tunnelService.diagnoseTunnelForwards(tunnelId);
    }

    @LogAnnotation
    @PostMapping("/diagnose-forwards/task")
    public R getTunnelForwardDiagnosisTask(@RequestBody Map<String, Object> params) {
        return tunnelService.getTunnelForwardDiagnosisTask(String.valueOf(params.get("taskId")));
    }

    @LogAnnotation
    @PostMapping("/diagnose-forwards/cancel")
    public R cancelTunnelForwardDiagnosisTask(@RequestBody Map<String, Object> params) {
        return tunnelService.cancelTunnelForwardDiagnosisTask(String.valueOf(params.get("taskId")));
    }

}

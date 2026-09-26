package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsDeploymentDto;
import com.admin.common.dto.VpsHostDto;
import com.admin.common.dto.VpsHostUpdateDto;
import com.admin.common.dto.VpsFingerprintConfirmDto;
import com.admin.common.lang.R;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.JwtUtil;
import com.admin.service.VpsDeploymentService;
import com.admin.service.VpsHostService;
import com.admin.service.VpsTerminalTicketService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** VPS hosting API. Authorization is enforced by VpsHostService per resource. */
@RestController
@RequestMapping("/api/v1/vps")
public class VpsController {

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private VpsDeploymentService vpsDeploymentService;

    @Resource
    private VpsTerminalTicketService vpsTerminalTicketService;

    @LogAnnotation
    @PostMapping("/list")
    public R list() {
        return vpsHostService.listHosts();
    }

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody VpsHostDto hostDto) {
        return vpsHostService.createHost(hostDto);
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody VpsHostUpdateDto hostDto) {
        return vpsHostService.updateHost(hostDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@Validated @RequestBody VpsActionDto actionDto) {
        return vpsHostService.deleteHost(actionDto);
    }

    @LogAnnotation
    @PostMapping("/check")
    public R check(@Validated @RequestBody VpsActionDto actionDto) {
        return vpsHostService.checkHost(actionDto);
    }

    /**
     * Browsers cannot put an Authorization header on a WebSocket upgrade. Issue
     * a one-time 60-second ticket instead of putting the long-lived login JWT
     * into the terminal URL.
     */
    @LogAnnotation
    @PostMapping("/terminal-ticket")
    public R createTerminalTicket(@Validated @RequestBody VpsActionDto actionDto) {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        Long userId = JwtUtil.getUserIdFromToken(token);
        boolean administrator = Objects.equals(JwtUtil.getRoleIdFromToken(token), 0);
        if (!vpsHostService.canOperate(userId, administrator, actionDto.getId())) {
            return R.err(403, "没有连接此 VPS 的权限");
        }
        if (!vpsHostService.isFingerprintVerified(vpsHostService.getById(actionDto.getId()))) {
            return R.err("请先检测并确认 SSH 主机指纹，再打开终端");
        }
        try {
            VpsTerminalTicketService.IssuedTicket ticket = vpsTerminalTicketService.issue(
                    userId, administrator, actionDto.getId());
            Map<String, Object> response = new HashMap<>();
            response.put("terminalTicket", ticket.getValue());
            response.put("expiresAt", ticket.getExpiresAt());
            return R.ok(response);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return R.err(exception.getMessage());
        }
    }

    @LogAnnotation
    @PostMapping("/reset-fingerprint")
    public R resetFingerprint(@Validated @RequestBody VpsActionDto actionDto) {
        return vpsHostService.resetFingerprint(actionDto);
    }

    @LogAnnotation
    @PostMapping("/confirm-fingerprint")
    public R confirmFingerprint(@Validated @RequestBody VpsFingerprintConfirmDto confirmDto) {
        return vpsHostService.confirmFingerprint(confirmDto);
    }

    @LogAnnotation
    @PostMapping("/assignable-users")
    public R assignableUsers() {
        return vpsHostService.listAssignableUsers();
    }

    @LogAnnotation
    @PostMapping("/deploy")
    public R deploy(@Validated @RequestBody VpsDeploymentDto deploymentDto) {
        return vpsDeploymentService.createTask(deploymentDto);
    }

    @LogAnnotation
    @PostMapping("/tasks")
    public R tasks(@Validated @RequestBody VpsActionDto actionDto) {
        return vpsDeploymentService.listTasks(actionDto);
    }
}

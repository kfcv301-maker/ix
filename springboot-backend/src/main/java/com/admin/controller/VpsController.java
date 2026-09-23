package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.VpsActionDto;
import com.admin.common.dto.VpsDeploymentDto;
import com.admin.common.dto.VpsHostDto;
import com.admin.common.dto.VpsHostUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.VpsDeploymentService;
import com.admin.service.VpsHostService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** VPS hosting API. Authorization is enforced by VpsHostService per resource. */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/vps")
public class VpsController {

    @Resource
    private VpsHostService vpsHostService;

    @Resource
    private VpsDeploymentService vpsDeploymentService;

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

    @LogAnnotation
    @PostMapping("/reset-fingerprint")
    public R resetFingerprint(@Validated @RequestBody VpsActionDto actionDto) {
        return vpsHostService.resetFingerprint(actionDto);
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

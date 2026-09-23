package com.admin.common.task;

import com.admin.service.VpsHostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** Periodic authenticated SSH availability check for active entrusted VPSes. */
@Slf4j
@Component
public class VpsHealthCheckTask {

    @Resource
    private VpsHostService vpsHostService;

    @Scheduled(initialDelay = 30_000L, fixedDelay = 60_000L)
    public void checkHosts() {
        try {
            vpsHostService.checkAllActiveHosts();
        } catch (Exception exception) {
            log.warn("VPS 定时检测失败: {}", exception.getMessage());
        }
    }
}

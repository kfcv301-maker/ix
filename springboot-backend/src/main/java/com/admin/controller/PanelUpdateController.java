package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.service.PanelUpdateService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/** Administrator-only entry point for the internal panel updater. */
@RestController
@RequestMapping("/api/v1/panel-update")
public class PanelUpdateController {

    @Resource
    private PanelUpdateService panelUpdateService;

    @RequireRole
    @PostMapping("/status")
    public R status() {
        return panelUpdateService.getStatus();
    }

    @RequireRole
    @PostMapping("/start")
    public R start() {
        return panelUpdateService.startUpdate();
    }
}

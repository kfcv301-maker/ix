package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpsSshServiceImplTest {

    @Test
    void panelTemplateDownloadsThenRunsInstallerWithBash() {
        String script = ReflectionTestUtils.invokeMethod(new VpsSshServiceImpl(), "resolveTemplate", "flux_panel");

        assertNotNull(script);
        assertTrue(script.contains("curl -fsSL --retry 3 https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh -o \"$panel_installer\""));
        assertTrue(script.contains("bash \"$panel_installer\" install"));
        assertFalse(script.contains("panel_install.sh | sh"));
    }

    @Test
    void dockerTemplateRejectsUnsupportedDebianBeforeInstallerRuns() {
        String script = ReflectionTestUtils.invokeMethod(new VpsSshServiceImpl(), "resolveTemplate", "docker");

        assertNotNull(script);
        assertTrue(script.indexOf("${VERSION_ID:-}") < script.indexOf("https://get.docker.com"));
        assertTrue(script.contains("Debian 11 已结束支持"));
        assertFalse(script.contains("panel_install.sh"));
    }
}

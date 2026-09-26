package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpsSshServiceImplTest {

    @Test
    void backendTemplateDownloadsThenRunsTheBackendOnlyInstallerWithBash() {
        VpsSshServiceImpl service = new VpsSshServiceImpl();
        ReflectionTestUtils.setField(service, "backendInstallRelease", "1.4.5");
        String script = ReflectionTestUtils.invokeMethod(service, "resolveTemplate", "backend");

        assertNotNull(script);
        assertTrue(script.contains("https://raw.githubusercontent.com/kfcv301-maker/ix/main/backend_install.sh"));
        assertFalse(script.contains("releases/download/"));
        assertTrue(script.contains("REPO_REF=1.4.5 bash \"$backend_installer\" install"));
        assertFalse(script.contains("panel_install.sh"));
        assertFalse(script.contains("get.docker.com"));
    }
}

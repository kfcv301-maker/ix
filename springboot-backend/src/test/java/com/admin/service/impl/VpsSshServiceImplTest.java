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
        assertTrue(script.contains("releases/download/1.4.5/backend_install.sh"));
        assertFalse(script.contains("/main/"));
        assertTrue(script.contains("backend_install.sh.sha256"));
        assertTrue(script.contains("sha256sum"));
        assertTrue(script.contains("REPO_REF=1.4.5 flock -n"));
        assertTrue(script.contains("bash \"$backend_installer\" install"));
        assertFalse(script.contains("panel_install.sh"));
        assertFalse(script.contains("get.docker.com"));
    }
}

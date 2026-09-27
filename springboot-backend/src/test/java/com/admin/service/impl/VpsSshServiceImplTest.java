package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.security.KeyPairGenerator;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;

class VpsSshServiceImplTest {

    @Test void fingerprintMatchesOpenSshAndLegacyPinsStillRequireTheSameKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = generator.generateKeyPair().getPublic();
        var other = generator.generateKeyPair().getPublic();
        String wire = ReflectionTestUtils.invokeMethod(VpsSshServiceImpl.class, "fingerprint", key);
        String legacy = ReflectionTestUtils.invokeMethod(VpsSshServiceImpl.class, "legacyFingerprint", key);
        assertNotNull(wire);
        assertTrue(FingerprintVerifier.getInstance(wire).verify("localhost", 22, key));
        assertFalse(FingerprintVerifier.getInstance(wire).verify("localhost", 22, other));
        assertNotEquals(legacy, wire);
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(VpsSshServiceImpl.class, "matchesFingerprint", wire, key));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(VpsSshServiceImpl.class, "matchesFingerprint", legacy, key));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(VpsSshServiceImpl.class, "matchesFingerprint", legacy, other));
    }

    @Test
    void backendTemplateDownloadsThenRunsTheBackendOnlyInstallerWithBash() {
        VpsSshServiceImpl service = new VpsSshServiceImpl();
        ReflectionTestUtils.setField(service, "backendInstallRelease", "1.4.5");
        String script = ReflectionTestUtils.invokeMethod(service, "resolveTemplate", "backend");

        assertNotNull(script);
        String stableUrl = "https://raw.githubusercontent.com/kfcv301-maker/ix/main/backend_install.sh";
        assertTrue(script.contains("curl -fsSL --retry 3 " + stableUrl + " -o"));
        assertTrue(script.contains("curl -fsSL --retry 3 " + stableUrl + ".sha256 -o"));
        assertFalse(script.contains("releases/download/"));
        assertTrue(script.contains("sha256sum"));
        assertTrue(script.contains("REPO_REF=1.4.5 flock -n"));
        assertTrue(script.contains("bash \"$backend_installer\" install"));
        assertFalse(script.contains("panel_install.sh"));
        assertFalse(script.contains("get.docker.com"));
    }
}

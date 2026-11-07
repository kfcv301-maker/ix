package com.admin.common.service;

import com.admin.common.dto.NodeInstallCommandDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.service.impl.NodeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AirportReporterInstallerTest {
    @Test void disabledNodesKeepNativeInstallerExactlyFreeOfObserver() {
        var sync=mock(AirportSyncService.class);var installer=new AirportReporterInstaller(sync);
        assertNull(installer.installation(1));assertEquals("",installer.shell(1));
        assertFalse(nativeScript(installer).contains("airport_observer"));
    }
    @Test void enabledNativeAndGitConfigPathsContainSameReviewedSourceWithoutAirportToken() throws Exception {
        var sync=mock(AirportSyncService.class);when(sync.needsReporter(1)).thenReturn(true);
        var installer=new AirportReporterInstaller(sync);var config=installer.installation(1);
        String source=new String(Base64.getDecoder().decode(config.getString("scriptBase64")),StandardCharsets.UTF_8);
        assertEquals(AirportSyncState.digest(source),config.getString("sha256"));
        assertTrue(source.contains("address-observation"));assertFalse(source.contains("node-address-sync/report"));
        String script=nativeScript(installer);
        assertTrue(script.contains(config.getString("scriptBase64")));
        assertTrue(script.contains("--install --root /etc/flux-panel-agent"));
        assertTrue(script.indexOf("--install --root")>script.indexOf("--disable-ddns"));
        String bash=System.getProperty("os.name").startsWith("Windows")?"C:/Program Files/Git/bin/bash.exe":"bash";
        Process process=new ProcessBuilder(bash,"-n").start();
        process.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));process.getOutputStream().close();
        assertEquals(0,process.waitFor());
    }
    private String nativeScript(AirportReporterInstaller installer) {
        var service=new NodeServiceImpl();ReflectionTestUtils.setField(service,"agentInstallRelease","1.4.15");
        ReflectionTestUtils.setField(service,"airportReporterInstaller",installer);
        var node=new Node();node.setId(1L);node.setSecret("isolated-node-secret");node.setDdnsEnabled(0);
        var request=new NodeInstallCommandDto();request.setId(1L);request.setPanelUrl("https://panel.example");
        R result=ReflectionTestUtils.invokeMethod(service,"buildInstallCommand",node,request);
        assertEquals(0,result.getCode());return result.getData().toString();
    }
}

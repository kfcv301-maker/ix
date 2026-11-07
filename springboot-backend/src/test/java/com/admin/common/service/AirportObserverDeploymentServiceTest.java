package com.admin.common.service;

import com.admin.entity.Node;
import com.admin.entity.VpsHost;
import com.admin.mapper.NodeMapper;
import com.admin.service.VpsHostService;
import com.admin.service.VpsSshService;
import org.junit.jupiter.api.Test;
import com.alibaba.fastjson2.JSONObject;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AirportObserverDeploymentServiceTest {
    @Test void onlinePreparationOnlyCallsObserverAndChecksNodeDigest() throws Exception {
        var sync=mock(AirportSyncService.class);var installer=mock(AirportReporterInstaller.class);var nodes=mock(NodeMapper.class);
        var hosts=mock(VpsHostService.class);var ssh=mock(VpsSshService.class);
        var host=new VpsHost();host.setId(2L);host.setStatus(1);var node=new Node();node.setId(3L);node.setSecret("private-node-fixture");
        when(sync.get(1L,3L)).thenReturn(Map.of());when(sync.needsReporter(3L)).thenReturn(true);
        when(hosts.findOperableHost(1L,true,2L)).thenReturn(host);when(hosts.isFingerprintVerified(host)).thenReturn(true);
        when(hosts.decryptSshPassword(host)).thenReturn("ssh-fixture");when(nodes.selectById(3L)).thenReturn(node);
        when(installer.bundle()).thenReturn(JSONObject.of("scriptBase64","cGFzcw=="));
        when(ssh.installAddressObserver(any(),anyString(),anyString(),anyString())).thenReturn(new VpsSshService.CommandResult(true,"ready","pin"));
        var service=new AirportObserverDeploymentService(sync,installer,nodes,hosts,ssh);
        try {
            service.run(1L,3L,2L);
            verify(ssh).installAddressObserver(host,"ssh-fixture","cGFzcw==",AirportSyncState.digest(node.getSecret()));
            verify(ssh,never()).runTemplate(any(),any(),any(),any());
            clearInvocations(ssh);when(hosts.isFingerprintVerified(host)).thenReturn(false);service.run(1L,3L,2L);verifyNoInteractions(ssh);
            assertThrows(IllegalArgumentException.class,()->service.start(1L,3L,2L));
            when(sync.get(9L,3L)).thenThrow(new SecurityException());assertThrows(SecurityException.class,()->service.start(9L,3L,2L));
        } finally { service.close(); }
    }
}

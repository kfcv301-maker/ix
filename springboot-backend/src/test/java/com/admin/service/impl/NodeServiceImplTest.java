package com.admin.service.impl;

import com.admin.common.dto.NodeInstallCommandDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeServiceImplTest {

    @Test
    void installCommandVerifiesThePinnedReleaseScriptBeforeRunningIt() {
        NodeServiceImpl service = new NodeServiceImpl();
        ReflectionTestUtils.setField(service, "agentInstallRelease", "1.4.5");
        Node node = new Node();
        node.setSecret("node-secret");
        NodeInstallCommandDto request = new NodeInstallCommandDto();
        request.setPanelUrl("https://panel.example.com");

        R response = ReflectionTestUtils.invokeMethod(service, "buildInstallCommand", node, request);
        assertEquals(0, response.getCode());
        String command = (String) response.getData();
        assertTrue(command.contains("releases/download/1.4.5/install.sh"));
        assertTrue(command.contains("install.sh.sha256"));
        assertTrue(command.contains("AGENT_RELEASE_BASE='https://github.com/kfcv301-maker/ix/releases/download/1.4.5'"));
        assertFalse(command.contains("bash \"$agent_installer\" -- "));
    }
}

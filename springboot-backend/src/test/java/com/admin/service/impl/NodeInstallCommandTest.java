package com.admin.service.impl;

import com.admin.common.dto.NodeInstallCommandDto;
import com.admin.common.lang.R;
import com.admin.common.service.NodeAgentConfigService;
import com.admin.common.service.NodeInstallBootstrapService;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeInstallCommandTest {
    private NodeServiceImpl service;
    private Node node;
    private User issuer;
    @BeforeEach void setup() {
        service = spy(new NodeServiceImpl());
        UserMapper users = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "installBootstrapService", new NodeInstallBootstrapService());
        ReflectionTestUtils.setField(service, "agentInstallRelease", "1.4.15");
        ReflectionTestUtils.setField(service, "jwtSecret", "test-jwt-key");
        node = new Node(); node.setId(1L); node.setOwnerUserId(2L); node.setSecret("node-permanent-secret");
        issuer = new User(); issuer.setId(2L); issuer.setStatus(1); issuer.setRoleId(1); issuer.setTokenVersion(0);
        doReturn(node).when(service).getById(1L);
        when(users.selectById(2L)).thenReturn(issuer);
    }
    @Test void fixedGitCommandContainsOnlyPanelAndNodeInstallationIdentity() {
        node.setDdnsEnabled(1); node.setDdnsRecordName("relay.example.com");
        node.setDdnsToken(new AESCrypto("test-jwt-key:node-ddns:v1").encrypt("cf-permanent-secret"));
        R response = issue("https://panel.example.com");
        assertEquals(0, response.getCode());
        String command = (String) response.getData();
        assertTrue(command.length() < 500);
        assertTrue(command.contains("https://raw.githubusercontent.com/kfcv301-maker/ix/main/node_install.sh"));
        assertTrue(command.contains(" -o \"$f\" && bash \"$f\" --panel 'https://panel.example.com' --node-id 1 --install-key '"));
        assertTrue(command.contains(NodeAgentConfigService.installKey(node)));
        for (String hidden : new String[]{"node-permanent-secret", "cf-permanent-secret", "relay.example.com", "1.4.15", "| bash"}) assertFalse(command.contains(hidden));
    }
    @Test void commandIsStableAcrossPasswordReleaseSettingsAndIssuerChanges() {
        String command = (String) issue("https://panel.example.com").getData();
        issuer.setTokenVersion(42);
        ReflectionTestUtils.setField(service, "agentInstallRelease", "1.4.16");
        node.setDdnsEnabled(1); node.setDdnsRecordName("changed.example.com");
        node.setDdnsToken(new AESCrypto("test-jwt-key:node-ddns:v1").encrypt("new-provider-token"));
        node.setTcpTuningProfile("tiny");
        assertEquals(command, issue("https://panel.example.com/").getData());
        issuer.setRoleId(0);
        assertEquals(command, issue("https://panel.example.com").getData());
        node.setDdnsEnabled(0);
        ReflectionTestUtils.setField(service, "jwtSecret", "rotated-panel-key");
        assertEquals(command, issue("https://panel.example.com").getData());
        node.setSecret("explicitly-rotated-node-identity");
        assertNotEquals(command, issue("https://panel.example.com").getData());
    }
    @Test void preservesHttpOriginAndCustomPort() {
        assertTrue(((String) issue("http://127.0.0.1:8080/").getData()).contains("--panel 'http://127.0.0.1:8080'"));
    }
    @Test void nonOwnersCannotObtainCommandsAndUntrustedOriginsFail() {
        node.setOwnerUserId(99L);
        assertEquals(403, issue("https://panel.example.com").getCode());
        node.setOwnerUserId(2L);
        for (String origin : new String[]{"https://user:pass@panel.example.com", "https://panel.example.com?x=1", "https://panel.example.com/#x", "https://panel.example.com\nrun", "file:///tmp/script"}) assertNotEquals(0, issue(origin).getCode(), origin);
        issuer.setStatus(0);
        assertEquals(403, issue("https://panel.example.com").getCode());
    }
    @Test void existingVersionOneCommandsRemainCompatibleAndKeepRevocationChecks() throws Exception {
        String origin = "https://panel.example.com";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-jwt-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("node-install:v1:1:node-permanent-secret:2:0:" + origin).getBytes(StandardCharsets.UTF_8)));
        String first = service.getReusableInstallScript(1L, 2L, origin, token);
        assertNotNull(first);
        assertTrue(first.contains("install.sh.sha256"));
        assertTrue(first.contains("--token 'node-permanent-secret'"));
        assertEquals(first, service.getReusableInstallScript(1L, 2L, origin, token));
        node.setTcpTuningProfile("tiny");
        assertTrue(service.getReusableInstallScript(1L, 2L, origin, token).contains("--tcp-profile 'tiny'"));
        assertNull(service.getReusableInstallScript(1L, 2L, "https://other.example.com", token));
        issuer.setTokenVersion(1);
        assertNull(service.getReusableInstallScript(1L, 2L, origin, token));
    }
    private R issue(String origin) {
        NodeInstallCommandDto request = new NodeInstallCommandDto(); request.setId(1L); request.setPanelUrl(origin);
        try (var jwt = mockStatic(JwtUtil.class)) {
            jwt.when(JwtUtil::getUserIdFromToken).thenReturn(2);
            jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(issuer.getRoleId());
            return service.getInstallCommand(request);
        }
    }
}

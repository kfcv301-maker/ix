package com.admin.service.impl;

import com.admin.common.dto.NodeInstallCommandDto;
import com.admin.common.lang.R;
import com.admin.common.service.NodeInstallBootstrapService;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeInstallCommandTest {
    private NodeServiceImpl service;
    private Node node;
    private User issuer;
    private UserMapper users;

    @BeforeEach
    void setup() {
        service = spy(new NodeServiceImpl());
        users = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "installBootstrapService", new NodeInstallBootstrapService());
        ReflectionTestUtils.setField(service, "agentInstallRelease", "1.4.10");
        ReflectionTestUtils.setField(service, "jwtSecret", "test-jwt-key");
        node = new Node();
        node.setId(1L);
        node.setOwnerUserId(2L);
        node.setSecret("node-permanent-secret");
        issuer = new User();
        issuer.setId(2L);
        issuer.setStatus(1);
        issuer.setRoleId(1);
        issuer.setTokenVersion(0);
        doReturn(node).when(service).getById(1L);
        when(users.selectById(2L)).thenReturn(issuer);
    }

    @Test
    void shortCommandDoesNotExposePermanentCredentialsAndStillPinsAndChecksInstaller() {
        node.setDdnsEnabled(1);
        node.setDdnsRecordName("relay.example.com");
        node.setDdnsToken(new AESCrypto("test-jwt-key:node-ddns:v1").encrypt("cf-permanent-secret"));
        R response = issue("https://panel.example.com");
        String command = (String) response.getData();
        assertEquals(0, response.getCode());
        assertTrue(command.length() < 230, "short command length=" + command.length());
        assertFalse(command.contains("node-permanent-secret"));
        assertFalse(command.contains("cf-permanent-secret"));
        assertFalse(command.contains("| bash"));
        assertTrue(command.contains(" -o \"$f\" && bash \"$f\""));
        assertTrue(command.contains("trap 'rm -f \"$f\"' EXIT"));
        String script = service.claimInstallScript(token(command));
        assertNotNull(script);
        assertTrue(script.startsWith("#!/usr/bin/env bash\nset -eu;"));
        assertTrue(script.contains("releases/download/1.4.10/install.sh"));
        assertTrue(script.contains("install.sh.sha256"));
        assertTrue(script.contains("[ \"$expected\" = \"$actual\" ]"));
        assertTrue(script.contains("--token 'node-permanent-secret'"));
        assertTrue(script.contains("--cf-api-token 'cf-permanent-secret'"));
        assertNull(service.claimInstallScript(token(command)));
    }

    @Test
    void preservesHttpOriginsAndCustomPortsWithoutAddingATlsDeploymentPrerequisite() {
        R response = issue("http://127.0.0.1:8080/");
        String command = (String) response.getData();
        assertTrue(command.contains("http://127.0.0.1:8080/api/v1/node/bootstrap/"));
        String script = service.claimInstallScript(token(command));
        assertTrue(script.contains("--panel 'http://127.0.0.1:8080'"));
        assertTrue(script.contains("https://github.com/kfcv301-maker/ix/releases/download/1.4.10"));
    }

    @Test
    void capabilityIsRevokedByOwnerSecretAccountPasswordOrRoleChanges() {
        assertRevoked(() -> node.setOwnerUserId(99L));
        setup();
        assertRevoked(() -> node.setSecret("rotated-secret"));
        setup();
        assertRevoked(() -> issuer.setStatus(0));
        setup();
        assertRevoked(() -> issuer.setTokenVersion(1));
        setup();
        issuer.setRoleId(0);
        node.setOwnerUserId(99L);
        assertRevoked(() -> issuer.setRoleId(1));
    }

    @Test
    void removedNodesAndAccountsCannotDownloadTheirPreviouslyIssuedScript() {
        String token = token((String) issue("https://panel.example.com").getData());
        doReturn(null).when(service).getById(1L);
        assertNull(service.claimInstallScript(token));
        setup();
        token = token((String) issue("https://panel.example.com").getData());
        when(users.selectById(2L)).thenReturn(null);
        assertNull(service.claimInstallScript(token));
    }

    @Test
    void nonOwnersCannotIssueCapabilitiesAndInvalidOriginsNeverIssueCommands() {
        node.setOwnerUserId(99L);
        assertEquals(403, issue("https://panel.example.com").getCode());
        node.setOwnerUserId(2L);
        for (String origin : new String[]{"https://user:pass@panel.example.com", "https://panel.example.com?x=1",
                "https://panel.example.com/#x", "https://panel.example.com\nrun", "file:///tmp/script"}) {
            assertNotEquals(0, issue(origin).getCode(), origin);
        }
    }

    @Test
    void claimUsesCurrentSavedSettingsAndShellQuotesCredentialArguments() {
        String token = token((String) issue("https://panel.example.com").getData());
        node.setTcpTuningAutoEnabled(1);
        node.setTcpTuningProfileMin("tiny");
        node.setTcpTuningProfileMax("balanced");
        String script = service.claimInstallScript(token);
        assertTrue(script.contains("--tcp-auto-tune --tcp-profile-min 'tiny' --tcp-profile-max 'balanced'"));
        assertFalse(script.contains("--tcp-profile '"));
        node.setSecret("quote'$(touch /tmp/unwanted)");
        token = token((String) issue("https://panel.example.com").getData());
        assertTrue(service.claimInstallScript(token).contains("--token 'quote'\"'\"'$(touch /tmp/unwanted)'"));
    }

    private void assertRevoked(Runnable mutation) {
        String token = token((String) issue("https://panel.example.com").getData());
        mutation.run();
        assertNull(service.claimInstallScript(token));
        assertNull(service.claimInstallScript(token));
    }

    private R issue(String origin) {
        NodeInstallCommandDto request = new NodeInstallCommandDto();
        request.setId(1L);
        request.setPanelUrl(origin);
        try (var jwt = mockStatic(JwtUtil.class)) {
            jwt.when(JwtUtil::getUserIdFromToken).thenReturn(2);
            jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(issuer.getRoleId());
            return service.getInstallCommand(request);
        }
    }

    private String token(String command) {
        var matcher = Pattern.compile("/api/v1/node/bootstrap/([A-Za-z0-9_-]{32})").matcher(command);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}

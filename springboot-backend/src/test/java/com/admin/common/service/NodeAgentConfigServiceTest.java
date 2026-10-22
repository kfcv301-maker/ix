package com.admin.common.service;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.Node;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeAgentConfigServiceTest {
    private NodeAgentConfigService service;
    private NodeMapper nodes;
    private Node node;
    private User owner;
    @BeforeEach void setup() {
        service = new NodeAgentConfigService(); nodes = mock(NodeMapper.class);
        UserMapper users = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "jwtSecret", "panel-key");
        ReflectionTestUtils.setField(service, "agentInstallRelease", "1.4.15");
        node = new Node(); node.setId(7L); node.setSecret("node-Secret"); node.setOwnerUserId(2L);
        owner = new User(); owner.setId(2L); owner.setStatus(1); owner.setTokenVersion(0);
        when(nodes.selectById(7L)).thenReturn(node);
        when(nodes.selectOne(any())).thenReturn(node);
        when(users.selectById(2L)).thenReturn(owner);
    }
    @AfterEach void close() { service.close(); }
    @Test void credentialScopesCannotBeInterchangedOrUsedAcrossNodes() {
        String key = NodeAgentConfigService.installKey(node);
        assertNull(service.installationConfig(7L, node.getSecret()));
        assertNull(service.installationConfig(8L, key));
        assertNull(service.authenticatedRuntimeConfig(key));
        assertNull(service.authenticatedRuntimeConfig("node-secret"));
        assertNull(service.authenticatedRuntimeConfig(""));
        var installation = service.installationConfig(7L, key);
        assertEquals("node-Secret", installation.getString("secret"));
        assertEquals("1.4.15", installation.getString("agentRelease"));
        var runtime = service.authenticatedRuntimeConfig("node-Secret");
        assertFalse(runtime.containsKey("secret"));
        assertFalse(runtime.containsKey("agentRelease"));
    }
    @Test void fixedIdentityReturnsLatestDdnsWithoutDependingOnPassword() {
        String key = NodeAgentConfigService.installKey(node);
        String originalRevision = service.installationConfig(7L, key).getString("revision");
        node.setDdnsEnabled(1); node.setDdnsRecordName("current.example.com");
        node.setDdnsToken(new AESCrypto("panel-key:node-ddns:v1").encrypt("current-provider-token"));
        owner.setTokenVersion(100);
        var updated = service.installationConfig(7L, key);
        assertNotEquals(originalRevision, updated.getString("revision"));
        assertEquals("current-provider-token", updated.getJSONObject("ddns").getString("apiToken"));
        assertEquals(updated.getString("revision"), service.installationConfig(7L, key).getString("revision"));
        node.setDdnsEnabled(0);
        var disabled = service.authenticatedRuntimeConfig("node-Secret").getJSONObject("ddns");
        assertEquals("", disabled.getString("apiToken"));
        assertEquals("", disabled.getString("recordName"));
        assertFalse(disabled.getBooleanValue("enabled"));
    }
    @Test void explicitRevocationDeletedNodeAndDisabledOwnerRejectOldCredentials() {
        String key = NodeAgentConfigService.installKey(node);
        owner.setStatus(0);
        assertNull(service.installationConfig(7L, key));
        assertNull(service.authenticatedRuntimeConfig("node-Secret"));
        owner.setStatus(1); node.setSecret("rotated");
        assertNull(service.installationConfig(7L, key));
        assertNull(service.authenticatedRuntimeConfig("node-Secret"));
        when(nodes.selectById(7L)).thenReturn(null);
        assertNull(service.installationConfig(7L, key));
    }
    @Test void brokenProviderConfigurationNeverBecomesAnEmptyOrDisabledSuccess() {
        node.setDdnsEnabled(1); node.setDdnsRecordName("valid.example.com"); node.setDdnsToken("corrupt");
        var exception = assertThrows(IllegalStateException.class, () -> service.runtimeConfig(node));
        assertFalse(exception.getMessage().contains("corrupt"));
    }
}

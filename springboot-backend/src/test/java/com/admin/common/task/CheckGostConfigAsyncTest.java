package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.service.UserProtocolPolicyService;
import com.admin.common.service.UserTunnelAliasService;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.TunnelEntryNodeMapper;
import com.admin.mapper.UserTunnelAliasMapper;
import com.admin.service.*;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckGostConfigAsyncTest {
    private final CheckGostConfigAsync service = new CheckGostConfigAsync();
    private final ForwardService forwards = mock(ForwardService.class);
    private final TunnelService tunnels = mock(TunnelService.class);
    private final UserService users = mock(UserService.class);
    private final UserTunnelService grants = mock(UserTunnelService.class);
    private final UserTunnelAliasService aliases = mock(UserTunnelAliasService.class);
    private final UserProtocolPolicyService policies = mock(UserProtocolPolicyService.class);
    private final TunnelEntryNodeMapper entries = mock(TunnelEntryNodeMapper.class);
    private final NodeService nodes = mock(NodeService.class);
    private final SpeedLimitService speeds = mock(SpeedLimitService.class);
    private final UserProtocolPolicySyncService policySync = mock(UserProtocolPolicySyncService.class);
    private final ForwardPauseTaskService pauseTasks = mock(ForwardPauseTaskService.class);
    private final ForwardSyncTaskService syncTasks = mock(ForwardSyncTaskService.class);
    private final Node node = new Node();
    private final Forward forward = new Forward();
    private final Tunnel tunnel = new Tunnel();
    private final User owner = new User();
    private final UserTunnel grant = new UserTunnel();
    private final SpeedLimit speedLimit = new SpeedLimit();
    private final List<String> commands = new ArrayList<>();
    private final List<String> createdServices = new ArrayList<>();
    private final List<String> deletedServices = new ArrayList<>();
    private int proxyProtocolCapabilityRevision = 2;
    private String failedCommand;
    private String failureMessage = "节点不在线";

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "forwardService", forwards);
        ReflectionTestUtils.setField(service, "tunnelService", tunnels);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "userTunnelService", grants);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", aliases);
        ReflectionTestUtils.setField(service, "userProtocolPolicyService", policies);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        ReflectionTestUtils.setField(service, "nodeService", nodes);
        ReflectionTestUtils.setField(service, "speedLimitService", speeds);
        ReflectionTestUtils.setField(service, "userProtocolPolicySyncService", policySync);
        ReflectionTestUtils.setField(service, "forwardPauseTaskService", pauseTasks);
        ReflectionTestUtils.setField(service, "forwardSyncTaskService", syncTasks);
        node.setId(1L);
        when(nodes.getById("1")).thenReturn(node);
        tunnel.setId(42L); tunnel.setStatus(1); tunnel.setType(1); tunnel.setInNodeId(1L);
        tunnel.setOutNodeId(2L); tunnel.setOutIp("127.0.0.1"); tunnel.setProtocol("mtls");
        forward.setId(12L); forward.setUserId(7); forward.setTunnelId(42); forward.setStatus(1);
        forward.setInPort(18103); forward.setOutPort(18203); forward.setRemoteAddr("127.0.0.1:443");
        owner.setId(7L); owner.setStatus(1); owner.setRoleId(1); owner.setFlow(10L);
        grant.setId(9); grant.setUserId(7); grant.setTunnelId(42); grant.setStatus(1); grant.setFlow(10L);
        speedLimit.setId(8L); speedLimit.setTunnelId(42L);
        TunnelEntryNode entry = new TunnelEntryNode(); entry.setNodeId(1L); entry.setTunnelId(42L);
        when(entries.selectList(any())).thenReturn(List.of(entry));
        when(tunnels.list(any(QueryWrapper.class))).thenAnswer(call -> {
            QueryWrapper<Tunnel> query = call.getArgument(0);
            return query.getSqlSegment().contains("in_node_id")
                    || (tunnel.getType() == 2 && node.getId().equals(tunnel.getOutNodeId())) ? List.of(tunnel) : List.of();
        });
        when(tunnels.listByIds(anyCollection())).thenReturn(List.of(tunnel));
        when(forwards.list(any(QueryWrapper.class))).thenReturn(List.of(forward));
        when(forwards.listByIds(anyCollection())).thenReturn(List.of(forward));
        when(users.list(any(QueryWrapper.class))).thenReturn(List.of(owner));
        when(grants.list(any(QueryWrapper.class))).thenReturn(List.of(grant));
        when(policies.forForward(forward)).thenAnswer(call -> UserProtocolPolicyService.policy(owner, grant));
        when(speeds.list(any(QueryWrapper.class))).thenReturn(List.of(speedLimit));
        when(speeds.listByIds(anyCollection())).thenReturn(List.of(speedLimit));
    }

    private GostConfigDto inventory(String... names) {
        GostConfigDto config = new GostConfigDto();
        List<ConfigItem> services = new ArrayList<>();
        for (String name : names) { ConfigItem item = new ConfigItem(); item.setName(name); services.add(item); }
        config.setServices(services); config.setChains(List.of()); config.setLimiters(List.of());
        return config;
    }

    private MockedStatic<WebSocketServer> socket() {
        MockedStatic<WebSocketServer> socket = mockStatic(WebSocketServer.class);
        socket.when(() -> WebSocketServer.nodeConnectionIdentity(1L)).thenReturn("test-connection");
        socket.when(() -> WebSocketServer.send_msg(anyLong(), any(), anyString())).thenAnswer(call -> {
            String command = call.getArgument(2);
            commands.add(command);
            Object data = call.getArgument(1);
            if ("DeleteService".equals(command)) {
                deletedServices.addAll(((JSONObject) data).getList("services", String.class));
            }
            if ("AddService".equals(command)) {
                for (Object item : (JSONArray) data) {
                    JSONObject config = (JSONObject) item;
                    createdServices.add(config.getString("name"));
                    if (!config.getString("name").endsWith("_tls")) {
                        assertEquals(UserProtocolPolicyService.policy(owner, grant),
                                config.getJSONObject("metadata").getJSONObject("userProtocolPolicy"));
                        assertEquals(grant.getSpeedId() == null ? null : grant.getSpeedId().toString(), config.getString("limiter"));
                        if (forward.getProxyProtocol() != null && forward.getProxyProtocol() > 0
                                && config.getString("name").endsWith("_tcp")) {
                            assertEquals(forward.getProxyProtocol(), config.getJSONObject("handler")
                                    .getJSONObject("metadata").getInteger("proxyProtocol"));
                        }
                    }
                }
            }
            GostDto result = new GostDto();
            result.setMsg(command.equals(failedCommand) ? failureMessage : "OK");
            if ("UserProtocolPolicyCapabilities".equals(command)) result.setData(JSONObject.of("userProtocolPolicy", 1));
            if ("ForwardProxyProtocolCapabilities".equals(command)) result.setData(JSONObject.of("forwardProxyProtocol", proxyProtocolCapabilityRevision,
                    "proxyProtocolVersions", JSONArray.of(1, 2)));
            return result;
        });
        return socket;
    }

    private void reconcile(GostConfigDto config) {
        ReflectionTestUtils.invokeMethod(service, "syncMissingForwards", config, node, Set.of());
    }

    @Test void restoresOnlyUdpWhenTcpIsRunning() {
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("12_7_9_udp"), createdServices);
        assertEquals(List.of("AddService"), commands);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void partialTcpRecoveryPreservesConfiguredProxyVersion(int version) {
        forward.setProxyProtocol(version);
        try (var socket = socket()) { reconcile(inventory("12_7_9_udp")); }
        assertEquals(List.of("12_7_9_tcp"), createdServices);
        assertTrue(commands.contains("ForwardProxyProtocolCapabilities"));
    }

    @Test void proxyEnabledForwardCanRestoreOnlyUdpOnALegacyNode() {
        forward.setProxyProtocol(2); failedCommand = "ForwardProxyProtocolCapabilities";
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("12_7_9_udp"), createdServices);
        assertEquals(List.of("AddService"), commands);
    }

    @Test void unsupportedTcpRecoveryDoesNotRemoveLegacyServicesOrCreateChains() {
        forward.setProxyProtocol(2); tunnel.setType(2);
        failedCommand = "ForwardProxyProtocolCapabilities";
        try (var socket = socket()) { reconcile(inventory("12_7_9_udp")); }
        assertEquals(List.of("ForwardProxyProtocolCapabilities"), commands);
        assertTrue(createdServices.isEmpty());
        verifyNoInteractions(aliases);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void revisionOneTcpRecoveryPreservesExistingServicesAndStopsBeforeLegacyCleanupOrChainUpdates(int version) {
        forward.setProxyProtocol(version); tunnel.setType(2);
        proxyProtocolCapabilityRevision = 1;
        try (var socket = socket()) { reconcile(inventory("12_7_9_udp")); }
        assertEquals(List.of("ForwardProxyProtocolCapabilities"), commands);
        assertTrue(createdServices.isEmpty());
        assertTrue(deletedServices.isEmpty());
        verifyNoInteractions(aliases);
    }

    @Test void restoresOnlyTcpWhenUdpIsRunning() {
        try (var socket = socket()) { reconcile(inventory("12_7_9_udp")); }
        assertEquals(List.of("12_7_9_tcp"), createdServices);
        assertEquals(List.of("AddService"), commands);
    }

    @Test void restoresBothProtocolsWhenBothAreMissing() {
        try (var socket = socket()) { reconcile(inventory()); }
        assertEquals(List.of("12_7_9_tcp", "12_7_9_udp"), createdServices);
        assertEquals(List.of("AddService"), commands);
    }

    @Test void preservesBothRunningProtocols() {
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp", "12_7_9_udp")); }
        assertTrue(commands.isEmpty());
        verifyNoInteractions(aliases, policies);
    }

    @Test void restrictedUdpRestorationRetainsTlsAndUdpPolicy() {
        grant.setTlsPolicy("TLS_ONLY"); grant.setAllowUdp(false); grant.setProtocolPolicyRevision(7L);
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("UserProtocolPolicyCapabilities", "AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
    }

    @Test void administratorRetainsProtocolExemption() {
        owner.setRoleId(0); grant.setTlsPolicy("TLS_ONLY"); grant.setAllowUdp(false);
        try (var socket = socket()) { reconcile(inventory("12_7_9_udp")); }
        assertEquals(List.of("AddService"), commands);
        assertEquals(List.of("12_7_9_tcp"), createdServices);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingOwner", "disabledOwner", "expiredOwner", "ownerQuota", "missingGrant",
            "disabledGrant", "expiredGrant", "grantQuota", "pausedForward", "disabledTunnel"})
    void neverResurrectsIneligibleForwards(String reason) {
        switch (reason) {
            case "missingOwner" -> when(users.list(any(QueryWrapper.class))).thenReturn(List.of());
            case "disabledOwner" -> owner.setStatus(0);
            case "expiredOwner" -> owner.setExpTime(System.currentTimeMillis() - 1);
            case "ownerQuota" -> owner.setInFlow(10L * 1024 * 1024 * 1024);
            case "missingGrant" -> when(grants.list(any(QueryWrapper.class))).thenReturn(List.of());
            case "disabledGrant" -> grant.setStatus(0);
            case "expiredGrant" -> grant.setExpTime(System.currentTimeMillis() - 1);
            case "grantQuota" -> grant.setOutFlow(10L * 1024 * 1024 * 1024);
            case "pausedForward" -> forward.setStatus(0);
            case "disabledTunnel" -> tunnel.setStatus(0);
            default -> fail("Unknown case");
        }
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertTrue(commands.isEmpty());
        verifyNoInteractions(aliases, policies);
    }

    @Test void incompleteServicesInventoryIsNotAssumedEmpty() {
        GostConfigDto config = inventory(); config.setServices(null);
        try (var socket = socket()) { reconcile(config); }
        assertTrue(commands.isEmpty());
        verifyNoInteractions(forwards, aliases, policies);
    }

    @Test void incompleteChainsInventoryDoesNotCreateRelayIngress() {
        tunnel.setType(2);
        GostConfigDto config = inventory("12_7_9_tcp"); config.setChains(null);
        try (var socket = socket()) { reconcile(config); }
        assertTrue(commands.isEmpty());
        verifyNoInteractions(aliases, policies);
    }

    @Test void failedChainLeavesRunningTcpUntouchedAndDoesNotCreateUdp() {
        tunnel.setType(2); failedCommand = "AddChains";
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("AddChains"), commands);
        assertTrue(createdServices.isEmpty());
    }

    @Test void confirmsChainBeforeCreatingMissingProtocol() {
        tunnel.setType(2);
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("AddChains", "AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
    }

    @Test void restoresOnlyChainWhenBothProtocolsAreRunning() {
        tunnel.setType(2);
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp", "12_7_9_udp")); }
        assertEquals(List.of("AddChains"), commands);
        assertTrue(createdServices.isEmpty());
    }

    @Test void sameNodeRemoteFailureStopsChainAndIngressRecovery() {
        tunnel.setType(2); tunnel.setOutNodeId(1L); failedCommand = "AddService";
        try (var socket = socket()) { reconcile(inventory()); }
        assertEquals(List.of("AddService"), commands);
        assertEquals(List.of("12_7_9_tls"), createdServices);
    }

    @Test void sameNodeRemoteThenChainThenIngressAreAcknowledgedInOrder() {
        tunnel.setType(2); tunnel.setOutNodeId(1L);
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("AddService", "AddChains", "AddService"), commands);
        assertEquals(List.of("12_7_9_tls", "12_7_9_udp"), createdServices);
    }

    @Test void legacyCleanupMustBeAcknowledgedBeforeCreatingAnything() {
        when(aliases.removeLegacyOnNode(anyLong(), eq(forward), eq(tunnel), eq(grant), eq(true)))
                .thenReturn("历史授权服务清理尚未获节点确认");
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertTrue(commands.isEmpty());
    }

    @Test void staleInventoryDuplicateNeverFallsBackToUpdateOrDelete() {
        failedCommand = "AddService";
        failureMessage = "service 12_7_9_udp already exists";
        try (var socket = socket()) { reconcile(inventory("12_7_9_tcp")); }
        assertEquals(List.of("AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
    }

    @Test void failedLimiterAckDefersMissingUdpAndPreservesDurableRetries() {
        grant.setSpeedId(8);
        when(speeds.ensureLimiterOnNode(8L, 1L)).thenAnswer(call -> {
            commands.add("EnsureLimiter"); return R.err("节点不在线");
        });
        try (var socket = socket()) { service.cleanNodeConfigs("1", inventory("12_7_9_tcp")); }
        assertEquals(List.of("EnsureLimiter"), commands);
        assertTrue(createdServices.isEmpty());
        verify(pauseTasks).retryPendingForNode(1L);
        verify(syncTasks).retryPendingForNode(1L);
        verify(policySync).syncInventory(eq(1L), anyList());
    }

    @Test void confirmsLimiterAckBeforeCreatingMissingUdp() {
        grant.setSpeedId(8);
        when(speeds.ensureLimiterOnNode(8L, 1L)).thenAnswer(call -> {
            commands.add("EnsureLimiter"); return R.ok();
        });
        try (var socket = socket()) { service.cleanNodeConfigs("1", inventory("12_7_9_tcp")); }
        assertEquals(List.of("EnsureLimiter", "AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
    }

    @Test void reportedExistingLimiterAllowsMissingProtocolWithoutUpdatingLimiter() {
        grant.setSpeedId(8);
        GostConfigDto config = inventory("12_7_9_tcp");
        ConfigItem limiter = new ConfigItem(); limiter.setName("8"); config.setLimiters(List.of(limiter));
        try (var socket = socket()) { service.cleanNodeConfigs("1", config); }
        assertEquals(List.of("AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
        verify(speeds, never()).ensureLimiterOnNode(anyLong(), anyLong());
    }

    @Test void omittedLimiterInventoryDoesNotRestoreCappedProtocol() {
        grant.setSpeedId(8);
        GostConfigDto config = inventory("12_7_9_tcp"); config.setLimiters(null);
        try (var socket = socket()) { service.cleanNodeConfigs("1", config); }
        assertTrue(commands.isEmpty());
        assertTrue(createdServices.isEmpty());
        verify(speeds, never()).ensureLimiterOnNode(anyLong(), anyLong());
    }

    @Test void absentLimiterAckDoesNotRestoreCappedProtocol() {
        grant.setSpeedId(8);
        try (var socket = socket()) { service.cleanNodeConfigs("1", inventory("12_7_9_tcp")); }
        assertTrue(commands.isEmpty());
        assertTrue(createdServices.isEmpty());
        verify(speeds).ensureLimiterOnNode(8L, 1L);
    }

    @Test void incompleteInventoryDoesNotSuppressFollowingCompleteInventory() {
        grant.setSpeedId(8);
        when(speeds.ensureLimiterOnNode(8L, 1L)).thenReturn(R.ok());
        GostConfigDto incomplete = inventory("12_7_9_tcp");
        incomplete.setServices(null); incomplete.setLimiters(null);
        try (var socket = socket()) {
            service.cleanNodeConfigs("1", incomplete);
            assertTrue(commands.isEmpty());
            service.cleanNodeConfigs("1", inventory("12_7_9_tcp"));
        }
        assertEquals(List.of("AddService"), commands);
        assertEquals(List.of("12_7_9_udp"), createdServices);
        verify(pauseTasks, times(2)).retryPendingForNode(1L);
        verify(syncTasks, times(2)).retryPendingForNode(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void identicalInventoryFromReinstalledConnectionIsNeverSuppressed(boolean completelyEmpty) {
        GostConfigDto config = completelyEmpty ? inventory() : inventory("12_7_9_tcp");
        try (var socket = socket()) {
            socket.when(() -> WebSocketServer.nodeConnectionIdentity(1L)).thenReturn("first-machine");
            service.cleanNodeConfigs("1", config);
            int first = createdServices.size();
            assertTrue(first > 0);
            service.cleanNodeConfigs("1", config);
            assertEquals(first, createdServices.size(), "Same-connection startup duplicate should still be deduplicated");
            socket.when(() -> WebSocketServer.nodeConnectionIdentity(1L)).thenReturn("replacement-machine");
            service.cleanNodeConfigs("1", config);
            assertEquals(first * 2, createdServices.size(), "New connection must recover identical missing inventory immediately");
        }
        verify(pauseTasks, times(3)).retryPendingForNode(1L);
        verify(syncTasks, times(3)).retryPendingForNode(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unconfirmedLimiterDefersChainAndLeavesLiveLegacyIngressUntouched(boolean omittedInventory) {
        grant.setSpeedId(8); tunnel.setType(2);
        UserTunnelAliasMapper aliasMapper = mock(UserTunnelAliasMapper.class);
        when(aliasMapper.selectAliases(9, 7, 42)).thenReturn(List.of(8));
        UserTunnelAliasService actualAliases = new UserTunnelAliasService();
        ReflectionTestUtils.setField(actualAliases, "aliasMapper", aliasMapper);
        ReflectionTestUtils.setField(service, "userTunnelAliasService", actualAliases);
        GostConfigDto config = inventory("12_7_8_tcp", "12_7_8_udp");
        if (omittedInventory) config.setLimiters(null);
        else when(speeds.ensureLimiterOnNode(8L, 1L)).thenReturn(R.err("节点未确认"));
        try (var socket = socket()) { service.cleanNodeConfigs("1", config); }
        assertTrue(commands.isEmpty(), "Must not delete live alias protocols or create an ingress chain/service");
        assertTrue(createdServices.isEmpty());
        verifyNoInteractions(aliasMapper);
    }

    @Test void udpOnlyOrphanIsCleanedUsingSingletonProtocolCommands() {
        when(forwards.listByIds(anyCollection())).thenReturn(List.of());
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices", inventory("24_7_9_udp"), node);
        }
        assertEquals(List.of("24_7_9_tcp", "24_7_9_udp"), deletedServices);
        assertEquals(List.of("DeleteService", "DeleteService"), commands);
    }

    @Test void pairedOrphanInventoryIsDeduplicatedByExactBaseName() {
        when(forwards.listByIds(anyCollection())).thenReturn(List.of());
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices",
                    inventory("24_7_9_tcp", "24_7_9_udp", "24_7_9_udp"), node);
        }
        assertEquals(List.of("24_7_9_tcp", "24_7_9_udp"), deletedServices);
        assertEquals(2, commands.size());
        verify(forwards).listByIds(Set.of(24L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tcp", "udp"})
    void existingForwardWithSingleReportedProtocolIsNeverOrphanCleaned(String protocol) {
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices", inventory("12_7_9_" + protocol), node);
        }
        assertTrue(commands.isEmpty());
        assertTrue(deletedServices.isEmpty());
    }

    @Test void remoteAndChainNamesAreNotTreatedAsIngressOrphans() {
        when(forwards.listByIds(anyCollection())).thenReturn(List.of());
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices",
                    inventory("24_7_9_tls", "24_7_9_chains", "web_api"), node);
        }
        assertEquals(List.of("24_7_9_tls"), deletedServices);
        assertEquals(List.of("DeleteService"), commands);
    }

    @Test void missingServicesInventoryDoesNotTriggerOrphanDeletion() {
        GostConfigDto config = inventory(); config.setServices(null);
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices", config, node);
        }
        assertTrue(commands.isEmpty());
        verifyNoInteractions(forwards);
    }

    @Test void incompleteForwardLookupDoesNotTurnReportedServicesIntoOrphans() {
        when(forwards.listByIds(anyCollection())).thenReturn(null);
        try (var socket = socket()) {
            ReflectionTestUtils.invokeMethod(service, "cleanOrphanedServices", inventory("12_7_9_udp"), node);
        }
        assertTrue(commands.isEmpty());
        assertTrue(deletedServices.isEmpty());
    }
}

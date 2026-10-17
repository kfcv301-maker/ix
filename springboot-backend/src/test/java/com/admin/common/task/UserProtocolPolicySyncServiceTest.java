package com.admin.common.task;

import com.admin.common.dto.ConfigItem;
import com.admin.common.dto.GostDto;
import com.admin.common.service.UserProtocolPolicyService;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelIngressNodeResolver;
import com.admin.entity.*;
import com.admin.service.*;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserProtocolPolicySyncServiceTest {
    @Test void pendingGrantOnlyTargetsItsForwardsAndRequiresAllIngressAcks() {
        UserProtocolPolicySyncService service = new UserProtocolPolicySyncService();
        UserTunnelService grants = mock(UserTunnelService.class); ForwardService forwards = mock(ForwardService.class);
        TunnelService tunnels = mock(TunnelService.class); TunnelIngressNodeResolver resolver = mock(TunnelIngressNodeResolver.class);
        UserProtocolPolicyService policies = mock(UserProtocolPolicyService.class);
        ReflectionTestUtils.setField(service,"userTunnelService",grants); ReflectionTestUtils.setField(service,"forwardService",forwards);
        ReflectionTestUtils.setField(service,"tunnelService",tunnels); ReflectionTestUtils.setField(service,"ingressNodeResolver",resolver);
        ReflectionTestUtils.setField(service,"policyService",policies);
        UserTunnel grant = new UserTunnel(); grant.setId(9); grant.setUserId(7); grant.setTunnelId(42);
        grant.setTlsPolicy("TLS_ONLY"); grant.setAllowUdp(false); grant.setProtocolSyncGeneration("generation-1");
        User owner = new User(); owner.setRoleId(1); Tunnel tunnel = new Tunnel();
        Forward forward = new Forward(); forward.setId(12L); forward.setUserId(7); forward.setStatus(1);
        when(grants.getById(9)).thenReturn(grant); when(tunnels.getById(42)).thenReturn(tunnel);
        when(policies.owner(7)).thenReturn(owner); when(resolver.resolveNodeIds(tunnel)).thenReturn(new LinkedHashSet<>(List.of(3L,4L)));
        when(forwards.list(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class))).thenAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Forward>) call.getArgument(0);
            assertTrue(query.getSqlSegment().contains("user_id")); assertTrue(query.getSqlSegment().contains("tunnel_id"));
            assertTrue(query.getParamNameValuePairs().containsValue(7)); assertTrue(query.getParamNameValuePairs().containsValue(42));
            return List.of(forward);
        });
        List<UpdateWrapper<UserTunnel>> updates = new ArrayList<>();
        when(grants.update(isNull(),any())).thenAnswer(call -> { updates.add(call.getArgument(1)); return true; });
        GostDto ok = new GostDto(); ok.setMsg("OK"); GostDto offline = new GostDto(); offline.setMsg("节点不在线");
        var policy = UserProtocolPolicyService.policy(owner,grant);
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(()->GostUtil.SetUserProtocolPolicy(eq(3L),anyCollection(),eq(policy))).thenReturn(ok);
            commands.when(()->GostUtil.SetUserProtocolPolicy(eq(4L),anyCollection(),eq(policy))).thenReturn(offline);
            service.syncGrant(9);
            commands.verify(()->GostUtil.SetUserProtocolPolicy(3L,List.of("12_7_9_tcp"),policy));
            commands.verify(()->GostUtil.SetUserProtocolPolicy(3L,List.of("12_7_9_udp"),policy));
            commands.verify(()->GostUtil.SetUserProtocolPolicy(4L,List.of("12_7_9_tcp"),policy));
            commands.verify(()->GostUtil.SetUserProtocolPolicy(4L,List.of("12_7_9_udp"),policy));
            var update = updates.get(0); update.getSqlSet(); update.getSqlSegment();
            assertTrue(update.getParamNameValuePairs().containsValue(true));
            assertTrue(update.getParamNameValuePairs().containsValue("generation-1"));
            commands.when(()->GostUtil.SetUserProtocolPolicy(eq(4L),anyCollection(),eq(policy))).thenReturn(ok);
            service.syncGrant(9);
            updates.get(1).getSqlSet(); assertTrue(updates.get(1).getParamNameValuePairs().containsValue(false));
        }
    }

    @Test void reconnectReconcilesOnlyOwnedIngressServicesAndKeepsAliasNames() {
        UserProtocolPolicySyncService service = new UserProtocolPolicySyncService();
        ForwardService forwards = mock(ForwardService.class); TunnelService tunnels = mock(TunnelService.class);
        UserProtocolPolicyService policies = mock(UserProtocolPolicyService.class);
        TunnelIngressNodeResolver resolver = mock(TunnelIngressNodeResolver.class);
        ReflectionTestUtils.setField(service,"forwardService",forwards); ReflectionTestUtils.setField(service,"tunnelService",tunnels);
        ReflectionTestUtils.setField(service,"policyService",policies); ReflectionTestUtils.setField(service,"ingressNodeResolver",resolver);
        Forward f = new Forward(); f.setId(12L); f.setUserId(7); f.setTunnelId(42); Tunnel tunnel = new Tunnel();
        when(forwards.getById(12L)).thenReturn(f); when(tunnels.getById(42)).thenReturn(tunnel);
        when(resolver.resolveNodeIds(tunnel)).thenReturn(Set.of(3L));
        User owner = new User(); owner.setRoleId(0); var policy = UserProtocolPolicyService.policy(owner,null);
        when(policies.forForward(f)).thenReturn(policy);
        List<ConfigItem> items = new ArrayList<>();
        for (String name : List.of("12_7_8_tcp","12_7_8_tls","12_99_8_tcp","web_api")) {
            ConfigItem item = new ConfigItem(); item.setName(name); items.add(item);
        }
        GostDto ok = new GostDto(); ok.setMsg("OK");
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(()->GostUtil.SetUserProtocolPolicy(anyLong(),anyCollection(),any())).thenReturn(ok);
            service.syncInventory(3L,items);
            commands.verify(()->GostUtil.SetUserProtocolPolicy(3L,List.of("12_7_8_tcp"),policy));
            commands.verifyNoMoreInteractions();
        }
    }

    @Test void tcpOnlyInventoryTightensExistingServiceEvenWhenUdpCannotRecover() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        List<String> applied = new ArrayList<>();
        try (var commands = mockStatic(GostUtil.class)) {
            // Model strict Go batch validation: an absent UDP rejects the whole
            // request, so the TCP policy would remain unchanged in the old code.
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenAnswer(call -> {
                Collection<String> names = call.getArgument(1);
                if (names.contains("12_7_8_udp")) return reply("service 12_7_8_udp not found");
                applied.addAll(names);
                return reply("OK");
            });
            fixture.service.syncInventory(3L, inventory("12_7_8_tcp"));
            assertEquals(List.of("12_7_8_tcp"), applied);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_8_tcp"), policy));
            commands.verifyNoMoreInteractions();
        }
    }

    @Test void udpOnlyInventoryUpdatesActualAliasWithoutInventingTcp() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenReturn(reply("OK"));
            fixture.service.syncInventory(3L, inventory("12_7_8_udp", "12_7_8_udp", "12_7_8_tls", "12_99_8_udp", "web_api"));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_8_udp"), policy));
            commands.verifyNoMoreInteractions();
        }
    }

    @Test void inventoryRetainsBothCurrentAndLegacyActualProtocolNames() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenReturn(reply("OK"));
            fixture.service.syncInventory(3L, inventory("12_7_8_tcp", "12_7_9_udp"));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_8_tcp", "12_7_9_udp"), policy));
        }
    }

    @Test void inventoryRaceRemovesOnlyDisappearedSideAndStillTightensExistingTcp() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp", "12_7_9_udp"), policy))
                    .thenReturn(reply("service 12_7_9_udp not found"));
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy)).thenReturn(reply("OK"));
            fixture.service.syncInventory(3L, inventory("12_7_9_tcp", "12_7_9_udp"));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp", "12_7_9_udp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy));
            commands.verifyNoMoreInteractions();
        }
    }

    @Test void inventoryUnsupportedCommandRecordsDurablePendingRatherThanTreatingItAsMissing() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_8_udp"), policy))
                    .thenReturn(reply("未知命令类型: SetUserProtocolPolicy"));
            fixture.service.syncInventory(3L, inventory("12_7_8_udp"));
            assertTrue(fixture.pending());
        }
    }

    @Test void pendingWorkerStillTightensTcpWhenUdpIsAbsent() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy)).thenReturn(reply("OK"));
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy)).thenReturn(reply("service 12_7_9_udp not found"));
            fixture.service.syncGrant(9);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy));
            assertFalse(fixture.pending());
        }
    }

    @Test void pendingWorkerStillTightensUdpWhenTcpIsAbsent() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        assertFalse(policy.getBooleanValue("allowUdp"));
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy)).thenReturn(reply("service 12_7_9_tcp not found"));
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy)).thenReturn(reply("OK"));
            fixture.service.syncGrant(9);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy));
            assertFalse(fixture.pending());
        }
    }

    @Test void missingNameIsRemovedFromStrictBatchSoOtherExistingRulesCanReceivePolicy() {
        Fixture fixture = new Fixture();
        Forward other = new Forward(); other.setId(13L); other.setUserId(7); other.setTunnelId(42); other.setStatus(1);
        when(fixture.forwards.list(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of(fixture.forward, other));
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenAnswer(call -> {
                Collection<String> names = call.getArgument(1);
                if (names.contains("12_7_9_tcp")) return reply("service 12_7_9_tcp not found");
                return reply("OK");
            });
            fixture.service.syncGrant(9);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp", "13_7_9_tcp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("13_7_9_tcp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp", "13_7_9_udp"), policy));
            assertFalse(fixture.pending());
        }
    }

    @Test void bothMissingServicesClearOnlyPolicyPendingWithoutCreatingOrActivatingRules() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenAnswer(call -> {
                Collection<String> names = call.getArgument(1);
                return reply("service " + names.iterator().next() + " not found");
            });
            fixture.service.syncGrant(9);
            assertFalse(fixture.pending());
            verify(fixture.forwards).list(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
            verifyNoMoreInteractions(fixture.forwards);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy));
            commands.verifyNoMoreInteractions();
        }
    }

    @Test void unsupportedOfflineTimeoutAndUnrelatedNotFoundNeverClearPendingOrBlockOtherProtocol() {
        for (String message : Arrays.asList(null, "节点不在线", "command timeout", "未知命令类型: SetUserProtocolPolicy",
                "service 99_7_9_tcp not found", "service 12_7_9_tcp not found; connection failed",
                "service 12_7_9_tcp does not support user policies")) {
            Fixture fixture = new Fixture();
            var policy = fixture.policy();
            try (var commands = mockStatic(GostUtil.class)) {
                commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy))
                        .thenReturn(message == null ? null : reply(message));
                commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy)).thenReturn(reply("OK"));
                fixture.service.syncGrant(9);
                commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy));
                assertTrue(fixture.pending(), "must retain pending for " + message);
            }
        }
    }

    @Test void requestExceptionKeepsPendingAndStillUpdatesOtherProtocol() {
        Fixture fixture = new Fixture();
        var policy = fixture.policy();
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy)).thenThrow(new IllegalStateException("offline"));
            commands.when(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy)).thenReturn(reply("OK"));
            fixture.service.syncGrant(9);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy));
            assertTrue(fixture.pending());
        }
    }

    @Test void administratorRemainsUnrestrictedOnSingleProtocolInventoryAndPendingWorker() {
        Fixture fixture = new Fixture();
        fixture.owner.setRoleId(0);
        var policy = fixture.policy();
        assertEquals("UNRESTRICTED", policy.getString("tls"));
        assertTrue(policy.getBooleanValue("allowUdp"));
        assertTrue(policy.getBooleanValue("administrator"));
        try (var commands = mockStatic(GostUtil.class)) {
            commands.when(() -> GostUtil.SetUserProtocolPolicy(eq(3L), anyCollection(), eq(policy))).thenReturn(reply("OK"));
            fixture.service.syncInventory(3L, inventory("12_7_8_udp"));
            fixture.service.syncGrant(9);
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_8_udp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_tcp"), policy));
            commands.verify(() -> GostUtil.SetUserProtocolPolicy(3L, List.of("12_7_9_udp"), policy));
            assertFalse(fixture.pending());
        }
    }

    private static List<ConfigItem> inventory(String... names) {
        return Arrays.stream(names).map(name -> { ConfigItem item = new ConfigItem(); item.setName(name); return item; }).toList();
    }

    private static GostDto reply(String message) {
        GostDto result = new GostDto(); result.setMsg(message); return result;
    }

    private static class Fixture {
        final UserProtocolPolicySyncService service = new UserProtocolPolicySyncService();
        final UserTunnelService grants = mock(UserTunnelService.class);
        final ForwardService forwards = mock(ForwardService.class);
        final TunnelService tunnels = mock(TunnelService.class);
        final TunnelIngressNodeResolver resolver = mock(TunnelIngressNodeResolver.class);
        final UserProtocolPolicyService policies = mock(UserProtocolPolicyService.class);
        final UserProtocolPolicySyncService self = mock(UserProtocolPolicySyncService.class);
        final UserTunnel grant = new UserTunnel();
        final User owner = new User();
        final Forward forward = new Forward();
        final Tunnel tunnel = new Tunnel();
        final List<UpdateWrapper<UserTunnel>> updates = new ArrayList<>();

        Fixture() {
            ReflectionTestUtils.setField(service, "userTunnelService", grants);
            ReflectionTestUtils.setField(service, "forwardService", forwards);
            ReflectionTestUtils.setField(service, "tunnelService", tunnels);
            ReflectionTestUtils.setField(service, "ingressNodeResolver", resolver);
            ReflectionTestUtils.setField(service, "policyService", policies);
            ReflectionTestUtils.setField(service, "self", self);
            grant.setId(9); grant.setUserId(7); grant.setTunnelId(42);
            grant.setTlsPolicy("TLS_ONLY"); grant.setAllowUdp(false); grant.setProtocolPolicyRevision(11L);
            grant.setProtocolSyncGeneration("generation-2"); grant.setProtocolSyncPending(false);
            owner.setRoleId(1);
            forward.setId(12L); forward.setUserId(7); forward.setTunnelId(42); forward.setStatus(1);
            when(grants.getById(9)).thenReturn(grant);
            when(forwards.getById(12L)).thenReturn(forward);
            when(forwards.list(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class))).thenReturn(List.of(forward));
            when(tunnels.getById(42)).thenReturn(tunnel);
            when(resolver.resolveNodeIds(tunnel)).thenReturn(Set.of(3L));
            when(policies.owner(7)).thenReturn(owner);
            when(policies.grant(forward)).thenReturn(grant);
            when(policies.forForward(forward)).thenAnswer(call -> policy());
            when(grants.update(isNull(), any())).thenAnswer(call -> { updates.add(call.getArgument(1)); return true; });
        }

        com.alibaba.fastjson2.JSONObject policy() { return UserProtocolPolicyService.policy(owner, grant); }

        boolean pending() {
            assertFalse(updates.isEmpty());
            UpdateWrapper<UserTunnel> update = updates.get(updates.size() - 1);
            update.getSqlSet(); update.getSqlSegment();
            assertTrue(update.getParamNameValuePairs().containsValue("generation-2"));
            return update.getParamNameValuePairs().containsValue(true);
        }
    }
}

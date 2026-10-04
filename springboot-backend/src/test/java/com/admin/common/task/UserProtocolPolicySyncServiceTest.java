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
            commands.verify(()->GostUtil.SetUserProtocolPolicy(3L,List.of("12_7_9_tcp","12_7_9_udp"),policy));
            commands.verify(()->GostUtil.SetUserProtocolPolicy(4L,List.of("12_7_9_tcp","12_7_9_udp"),policy));
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
            commands.verify(()->GostUtil.SetUserProtocolPolicy(3L,List.of("12_7_8_tcp","12_7_8_udp"),policy));
            commands.verifyNoMoreInteractions();
        }
    }
}

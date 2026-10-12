package com.admin.service.impl;

import com.admin.common.dto.UserTunnelDto;
import com.admin.common.dto.UserTunnelUpdateDto;
import com.admin.common.service.UserProtocolPolicyService;
import com.admin.common.task.UserProtocolPolicySyncService;
import com.admin.common.task.ForwardPauseTaskService;
import com.admin.common.lang.R;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserTunnelServiceImplTest {
    @Test void protocolUpdateOnlyWritesTheSelectedGrantAndPreservesTrafficCounters() {
        UserTunnelServiceImpl service = spy(new UserTunnelServiceImpl());
        UserService users = mock(UserService.class);
        UserProtocolPolicyService policy = new UserProtocolPolicyService();
        UserProtocolPolicySyncService sync = mock(UserProtocolPolicySyncService.class);
        ReflectionTestUtils.setField(policy,"userService",users);
        ReflectionTestUtils.setField(service,"userProtocolPolicyService",policy);
        ReflectionTestUtils.setField(service,"userProtocolPolicySyncService",sync);
        ReflectionTestUtils.setField(service,"forwardPauseTaskService",mock(ForwardPauseTaskService.class));
        User owner = new User(); owner.setRoleId(1); when(users.getById(7)).thenReturn(owner);
        UserTunnel grant = new UserTunnel(); grant.setId(9); grant.setUserId(7); grant.setTunnelId(42);
        grant.setTlsPolicy("UNRESTRICTED"); grant.setAllowUdp(true); grant.setInFlow(12345L); grant.setOutFlow(6789L);
        doReturn(grant).when(service).getById(9);
        doAnswer(call -> {
            UpdateWrapper<UserTunnel> update = call.getArgument(1);
            String sql = update.getSqlSet(); String filter = update.getSqlSegment();
            assertFalse(sql.contains("in_flow")); assertFalse(sql.contains("out_flow"));
            assertTrue(sql.contains("tls_policy")); assertTrue(sql.contains("protocol_sync_generation"));
            assertTrue(filter.contains("id")); assertTrue(update.getParamNameValuePairs().containsValue(9));
            assertTrue(update.getParamNameValuePairs().containsValue("TLS_ONLY")); return true;
        }).when(service).update(isNull(),any());
        UserTunnelUpdateDto edit = new UserTunnelUpdateDto(); edit.setId(9); edit.setFlow(100L); edit.setNum(10);
        edit.setTlsPolicy("TLS_ONLY"); edit.setAllowUdp(false);
        assertEquals(0,service.updateUserTunnel(edit).getCode());
        assertTrue(grant.getProtocolSyncPending()); assertNotNull(grant.getProtocolSyncGeneration());
        assertEquals(12345L,grant.getInFlow()); assertEquals(6789L,grant.getOutFlow());
        verify(sync).syncGrant(9); verifyNoMoreInteractions(sync);
    }

    @Test
    void privateTunnelCannotBeAssignedToAnotherUser() {
        TunnelService tunnelService = mock(TunnelService.class);
        UserTunnelServiceImpl service = new UserTunnelServiceImpl();
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);

        Tunnel tunnel = new Tunnel();
        tunnel.setOwnerUserId(7L);
        when(tunnelService.getById(42)).thenReturn(tunnel);

        UserTunnelDto assignment = new UserTunnelDto();
        assignment.setTunnelId(42);
        assignment.setUserId(8);
        R result = service.assignUserTunnel(assignment);

        assertEquals(403, result.getCode());
        assertEquals("用户自建隧道不能授权给其他用户", result.getMsg());
    }
}

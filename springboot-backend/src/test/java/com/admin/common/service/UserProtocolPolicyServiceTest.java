package com.admin.common.service;

import com.admin.entity.*;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProtocolPolicyServiceTest {
    private User user(int role) { User user = new User(); user.setRoleId(role); return user; }
    private UserTunnel grant() {
        UserTunnel grant = new UserTunnel(); grant.setUserId(7); grant.setTunnelId(42);
        grant.setTlsPolicy("TLS_ONLY"); grant.setAllowUdp(false); return grant;
    }

    @Test void administratorsAreExemptEvenIfTheGrantContainsRestrictions() {
        var policy = UserProtocolPolicyService.policy(user(0), grant());
        assertEquals("UNRESTRICTED", policy.getString("tls"));
        assertTrue(policy.getBooleanValue("allowUdp"));
    }

    @Test void regularUsersKeepBothIndependentRestrictions() {
        var policy = UserProtocolPolicyService.policy(user(1), grant());
        assertEquals("TLS_ONLY", policy.getString("tls")); assertFalse(policy.getBooleanValue("allowUdp"));
        assertTrue(UserProtocolPolicyService.restricted(policy));
    }

    @Test void omittedUpdateFieldsPreserveRestrictionsAndInvalidValuesAreRejected() {
        UserProtocolPolicyService service = new UserProtocolPolicyService();
        UserService users = mock(UserService.class); ReflectionTestUtils.setField(service,"userService",users);
        when(users.getById(7)).thenReturn(user(1)); UserTunnel grant = grant();
        service.apply(grant,null,null,true);
        assertEquals("TLS_ONLY",grant.getTlsPolicy()); assertFalse(grant.getAllowUdp());
        assertThrows(IllegalArgumentException.class,()->service.apply(grant,"typo",true,true));
        service.apply(grant,"TLS_DENY",true,true);
        assertEquals("TLS_DENY",grant.getTlsPolicy()); assertTrue(grant.getAllowUdp());
        when(users.getById(7)).thenReturn(user(0)); service.apply(grant,"TLS_ONLY",false,true);
        assertEquals("UNRESTRICTED",grant.getTlsPolicy()); assertTrue(grant.getAllowUdp());
    }

    @Test void forwardOwnerDeterminesThePolicyRegardlessOfWhoEditsTheForward() {
        UserProtocolPolicyService service = new UserProtocolPolicyService();
        UserService users = mock(UserService.class); UserTunnelService grants = mock(UserTunnelService.class);
        ReflectionTestUtils.setField(service,"userService",users); ReflectionTestUtils.setField(service,"userTunnelService",grants);
        Forward forward = new Forward(); forward.setUserId(7); forward.setTunnelId(42);
        when(users.getById(7)).thenReturn(user(1)); when(grants.getOne(any())).thenReturn(grant());
        assertEquals("TLS_ONLY",service.forForward(forward).getString("tls"));
        when(users.getById(7)).thenReturn(user(0)); clearInvocations(grants);
        assertFalse(UserProtocolPolicyService.restricted(service.forForward(forward)));
        verifyNoInteractions(grants);
    }
}

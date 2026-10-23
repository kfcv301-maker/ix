package com.admin.common.service;

import com.admin.entity.Tunnel;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.mapper.UserTunnelMapper;
import com.admin.service.TunnelEntryDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserIngressAddressServiceTest {
    private final UserIngressAddressService service = new UserIngressAddressService();
    private final UserTunnelMapper grants = mock(UserTunnelMapper.class);
    private final TunnelMapper tunnels = mock(TunnelMapper.class);
    private final TunnelEntryDomainService domains = mock(TunnelEntryDomainService.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "tunnelMapper", tunnels);
        ReflectionTestUtils.setField(service, "domainService", domains);
    }

    @Test void eachUserGetsTheirOwnDomainAndNeverRawMultiIngressOrAnotherUsersDomain() {
        Tunnel tunnel = tunnel(11, null);
        tunnel.setInIp("192.0.2.1,192.0.2.2");
        UserTunnel first = grant(7, 11, "CUSTOM");
        UserTunnel second = grant(8, 11, "CUSTOM");
        when(grants.selectList(any())).thenReturn(List.of(first, second));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel));
        when(domains.resolveAssignedDomains(List.of(first))).thenReturn(Map.of(11, "alice.example.com"));
        when(domains.resolveAssignedDomains(List.of(second))).thenReturn(Map.of(11, "bob.example.com"));
        assertEquals("alice.example.com", service.forUser(7).get(11).address());
        assertEquals("bob.example.com", service.forUser(8).get(11).address());
        assertEquals("192.0.2.1,192.0.2.2", tunnel.getInIp(), "display projection must not change routing data");
    }

    @Test void missingCustomOrDefaultDomainFailsClosedInsteadOfUsingRawIngress() {
        when(grants.selectList(any())).thenReturn(List.of(grant(7, 11, "CUSTOM"), grant(7, 12, "DEFAULT")));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null), tunnel(12, null)));
        var addresses = service.forUser(7);
        assertEquals("CUSTOM", addresses.get(11).mode());
        assertEquals("DEFAULT", addresses.get(12).mode());
        assertNull(addresses.get(11).address());
        assertNull(addresses.get(12).address());
    }

    @Test void explicitNoneAndLegacyNoneKeepTheirAuthorizedRawIngress() {
        when(grants.selectList(any())).thenReturn(List.of(grant(7, 11, "NONE"), grant(7, 12, null)));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null), tunnel(12, null)));
        assertEquals("base.example.com", service.forUser(7).get(11).address());
        assertEquals("base.example.com", service.forUser(7).get(12).address());
        verifyNoInteractions(domains);
    }

    @Test void defaultOnlyUsesTheAdministrativelyAssignedDefaultPoolEntry() {
        UserTunnel grant = grant(7, 11, "DEFAULT");
        when(grants.selectList(any())).thenReturn(List.of(grant));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null)));
        when(domains.resolveAssignedDomains(List.of(grant))).thenReturn(Map.of(11, "assigned-default.example.com"));
        assertEquals("assigned-default.example.com", service.forUser(7).get(11).address());
    }

    @Test void missingExpiredDisabledAndAnotherOwnersGrantsNeverAuthorizeSharedRawIngress() {
        UserTunnel expired = grant(7, 11, "NONE"); expired.setExpTime(1L);
        UserTunnel disabled = grant(7, 12, "NONE"); disabled.setStatus(0);
        when(grants.selectList(any())).thenReturn(List.of(expired, disabled, grant(8, 13, "NONE"), grant(7, 14, "NONE")));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null), tunnel(12, null), tunnel(13, null),
                tunnel(14, 8L), tunnel(15, null), tunnel(16, 7L)));
        var addresses = service.forUser(7);
        assertEquals(1, addresses.size());
        assertEquals("base.example.com", addresses.get(16).address(), "owner retains their own infrastructure access");
    }

    @Test void unknownModeCannotFallBackToRawIngress() {
        when(grants.selectList(any())).thenReturn(List.of(grant(7, 11, "corrupt")));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null)));
        assertEquals(UserIngressAddressService.UNASSIGNED, service.forUser(7).get(11));
    }

    @Test void oneDomainLookupIncludesOnlyAuthorizedVisibleAssignments() {
        UserTunnel custom = grant(7, 11, " custom ");
        UserTunnel defaultDomain = grant(7, 12, "default");
        UserTunnel expired = grant(7, 14, "CUSTOM"); expired.setExpTime(1L);
        UserTunnel disabled = grant(7, 15, "CUSTOM"); disabled.setStatus(0);
        when(grants.selectList(any())).thenReturn(List.of(custom, defaultDomain,
                grant(7, 13, "NONE"), expired, disabled, grant(7, 16, "CUSTOM"), grant(8, 17, "CUSTOM")));
        when(tunnels.selectList(any())).thenReturn(List.of(tunnel(11, null), tunnel(12, null),
                tunnel(13, null), tunnel(14, null), tunnel(15, null), tunnel(16, 8L), tunnel(17, null)));
        when(domains.resolveAssignedDomains(List.of(custom, defaultDomain)))
                .thenReturn(Map.of(11, "custom.example.com", 12, "default.example.com"));

        var addresses = service.forUser(7);
        assertEquals(3, addresses.size());
        assertEquals("custom.example.com", addresses.get(11).address());
        assertEquals("default.example.com", addresses.get(12).address());
        assertEquals("base.example.com", addresses.get(13).address());
        verify(domains).resolveAssignedDomains(List.of(custom, defaultDomain));
        verifyNoMoreInteractions(domains);
    }

    private static UserTunnel grant(int user, int tunnel, String mode) {
        UserTunnel grant = new UserTunnel(); grant.setUserId(user); grant.setTunnelId(tunnel);
        grant.setEntryAddressMode(mode); grant.setStatus(1); return grant;
    }
    private static Tunnel tunnel(long id, Long owner) {
        Tunnel tunnel = new Tunnel(); tunnel.setId(id); tunnel.setStatus(1); tunnel.setOwnerUserId(owner);
        tunnel.setInIp("base.example.com"); return tunnel;
    }
}

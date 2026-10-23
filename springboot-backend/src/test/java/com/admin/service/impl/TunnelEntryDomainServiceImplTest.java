package com.admin.service.impl;

import com.admin.entity.TunnelEntryDomain;
import com.admin.entity.UserTunnel;
import com.admin.mapper.TunnelEntryDomainMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TunnelEntryDomainServiceImplTest {
    private final TunnelEntryDomainServiceImpl service = new TunnelEntryDomainServiceImpl();
    private final TunnelEntryDomainMapper domains = mock(TunnelEntryDomainMapper.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "baseMapper", domains);
    }

    @Test void resolvesManyTunnelsInOneScopedReadAndKeepsCustomAndDefaultAssignmentsSeparate() {
        when(domains.selectList(any())).thenReturn(List.of(
                domain(101, 11, "base-a.example.com", 1, 1),
                domain(102, 11, "user-a.example.com", 1, 0),
                domain(201, 12, "base-b.example.com", 1, 1),
                domain(202, 12, "user-b.example.com", 1, 0)));
        var resolved = service.resolveAssignedDomains(List.of(
                grant(11, "CUSTOM", 102L), grant(12, "DEFAULT", 202L)));
        assertEquals(Map.of(11, "user-a.example.com", 12, "base-b.example.com"), resolved);
        ArgumentCaptor<QueryWrapper<TunnelEntryDomain>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(domains).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("status ="));
        assertTrue(query.getValue().getSqlSegment().contains("tunnel_id IN"));
        assertEquals(3, query.getValue().getParamNameValuePairs().size());
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(11L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(12L));
        verifyNoMoreInteractions(domains);
    }

    @Test void crossTunnelDeletedInactiveBlankAndMissingDomainsNeverFallBackToDefault() {
        when(domains.selectList(any())).thenReturn(List.of(
                domain(101, 11, "default.example.com", 1, 1),
                domain(102, 11, "revoked.example.com", 0, 0),
                domain(201, 12, "other-user.example.com", 1, 0),
                domain(301, 13, " ", 1, 1)));
        assertTrue(service.resolveAssignedDomains(List.of(grant(11, "CUSTOM", 201L),
                grant(12, "CUSTOM", 102L), grant(13, "DEFAULT", null),
                grant(14, "CUSTOM", 999L), grant(15, "DEFAULT", null))).isEmpty());
        assertNull(service.resolveAssignedDomain(grant(11, "CUSTOM", 102L)));
    }

    @Test void changedDomainIsVisibleOnTheNextRequestWithoutAnyCacheFlush() {
        when(domains.selectList(any())).thenReturn(
                List.of(domain(101, 11, "old.example.com", 1, 0)),
                List.of(domain(101, 11, "new.example.com", 1, 0)), List.of());
        UserTunnel assignment = grant(11, "CUSTOM", 101L);
        assertEquals("old.example.com", service.resolveAssignedDomain(assignment));
        assertEquals("new.example.com", service.resolveAssignedDomain(assignment));
        assertNull(service.resolveAssignedDomain(assignment));
    }

    @Test void duplicateDefaultsKeepTheExistingLowestIdSelection() {
        when(domains.selectList(any())).thenReturn(List.of(
                domain(105, 11, "later.example.com", 1, 1),
                domain(102, 11, "earlier.example.com", 1, 1)));
        assertEquals("earlier.example.com", service.resolveAssignedDomain(grant(11, " default ", null)));
    }

    @Test void invalidAndRawAssignmentsDoNotReadTheDomainPool() {
        assertNull(service.resolveAssignedDomain(null));
        assertNull(service.resolveAssignedDomain(new UserTunnel()));
        assertTrue(service.resolveAssignedDomains(null).isEmpty());
        assertTrue(service.resolveAssignedDomains(List.of()).isEmpty());
        var assignments = new ArrayList<UserTunnel>();
        assignments.add(null);
        assignments.add(new UserTunnel());
        assignments.add(grant(11, "NONE", 1L));
        assignments.add(grant(12, null, null));
        assignments.add(grant(13, "unknown", 1L));
        assignments.add(grant(14, "CUSTOM", null));
        assertTrue(service.resolveAssignedDomains(assignments).isEmpty());
        verifyNoInteractions(domains);
    }

    private static UserTunnel grant(int tunnelId, String mode, Long domainId) {
        UserTunnel grant = new UserTunnel();
        grant.setUserId(7); grant.setTunnelId(tunnelId);
        grant.setEntryAddressMode(mode); grant.setEntryDomainId(domainId);
        return grant;
    }

    private static TunnelEntryDomain domain(long id, long tunnelId, String name, int status, int isDefault) {
        TunnelEntryDomain domain = new TunnelEntryDomain();
        domain.setId(id); domain.setTunnelId(tunnelId); domain.setDomain(name);
        domain.setStatus(status); domain.setIsDefault(isDefault);
        return domain;
    }
}

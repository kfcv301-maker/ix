package com.admin.service.impl;

import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Tunnel;
import com.admin.mapper.*;
import com.admin.service.ForwardService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TunnelDeletionAtomicityTest {
    private TunnelServiceImpl service;
    private UserTunnelMapper grants;
    private TunnelEntryNodeMapper entries;
    private TunnelEntryDomainMapper domains;
    private MockedStatic<JwtUtil> jwt;
    private MockedStatic<WebSocketServer> browsers;

    @BeforeEach void setup() {
        service = spy(new TunnelServiceImpl());
        grants = mock(UserTunnelMapper.class); entries = mock(TunnelEntryNodeMapper.class);
        domains = mock(TunnelEntryDomainMapper.class);
        ReflectionTestUtils.setField(service, "userTunnelMapper", grants);
        ReflectionTestUtils.setField(service, "tunnelEntryNodeMapper", entries);
        ReflectionTestUtils.setField(service, "tunnelEntryDomainMapper", domains);
        ReflectionTestUtils.setField(service, "forwardService", mock(ForwardService.class));
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setOwnerUserId(7L);
        doReturn(tunnel).when(service).getById(11L);
        jwt = mockStatic(JwtUtil.class); browsers = mockStatic(WebSocketServer.class);
        jwt.when(JwtUtil::getUserIdFromToken).thenReturn(7);
        jwt.when(JwtUtil::getRoleIdFromToken).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void close() {
        TransactionSynchronizationManager.clearSynchronization(); browsers.close(); jwt.close();
    }

    @Test void failedFinalDeletionThrowsToRollBackEarlierGrantChangesWithoutDisconnectingBrowsers() {
        doReturn(false).when(service).removeById(11L);
        assertThrows(IllegalStateException.class, () -> service.deleteTunnel(11L));
        verify(grants).delete(any());
        verifyNoInteractions(entries, domains);
        browsers.verifyNoInteractions();
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test void relationFailureAlsoKeepsBrowserAccessUntilTransactionOutcomeIsKnown() {
        doReturn(true).when(service).removeById(11L);
        doThrow(new IllegalStateException("storage fixture failure")).when(domains).delete(any());
        assertThrows(IllegalStateException.class, () -> service.deleteTunnel(11L));
        browsers.verifyNoInteractions();
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test void successfulDeletionNotifiesBrowsersOnlyAfterCommit() {
        doReturn(true).when(service).removeById(11L);
        assertEquals(0, service.deleteTunnel(11L).getCode());
        verify(grants).delete(any()); verify(entries).delete(any()); verify(domains).delete(any());
        browsers.verifyNoInteractions();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        browsers.verify(() -> WebSocketServer.closeUserSessions(7L));
    }
}

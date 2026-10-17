package com.admin.common.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeInstallBootstrapServiceTest {
    @Test
    void expiresAtTenMinutesAndMalformedOrUnknownTokensNeverClaim() {
        AtomicLong time = new AtomicLong(1000);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> time.get());
        NodeInstallBootstrapService service = new NodeInstallBootstrapService(clock);
        String token = service.issue(1L, 2L, 0, "https://panel.example", "permanent-secret");
        assertTrue(token.matches("[A-Za-z0-9_-]{32}"));
        assertNull(service.claim("../../../secret"));
        assertNull(service.claim("A".repeat(32)));
        time.addAndGet(NodeInstallBootstrapService.LIFETIME.toMillis());
        assertNull(service.claim(token));
    }

    @Test
    void regeneratingForNodeRevokesOnlyThatNodesOldCommand() {
        NodeInstallBootstrapService service = new NodeInstallBootstrapService();
        String old = service.issue(1L, 2L, 0, "http://panel.example:8080", "old-secret");
        String other = service.issue(2L, 2L, 0, "http://panel.example:8080", "other-secret");
        String replacement = service.issue(1L, 3L, 4, "http://panel.example:8080", "new-secret");
        assertNull(service.claim(old));
        var install = service.claim(replacement);
        assertNotNull(install);
        assertEquals("http://panel.example:8080", install.panelUrl());
        assertTrue(install.matchesNodeSecret("new-secret"));
        assertFalse(install.matchesNodeSecret("old-secret"));
        assertFalse(install.matchesNodeSecret(null));
        assertNotNull(service.claim(other));
        assertNull(service.claim(replacement));
    }

    @Test
    void simultaneousDownloadsCanClaimExactlyOnce() throws Exception {
        NodeInstallBootstrapService service = new NodeInstallBootstrapService();
        String token = service.issue(1L, 2L, 0, "https://panel.example", "secret");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<NodeInstallBootstrapService.PendingInstall>>();
            for (int i = 0; i < 24; i++) results.add(pool.submit(() -> service.claim(token)));
            int claimed = 0;
            for (var result : results) if (result.get() != null) claimed++;
            assertEquals(1, claimed);
        }
    }

    @Test
    void boundedCapacityRecoversAfterExpiryWithoutBackgroundTasks() {
        AtomicLong time = new AtomicLong(1000);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> time.get());
        NodeInstallBootstrapService service = new NodeInstallBootstrapService(clock);
        for (long id = 1; id <= NodeInstallBootstrapService.MAX_PENDING; id++) {
            service.issue(id, 2L, 0, "https://panel.example", "secret");
        }
        assertThrows(IllegalStateException.class,
                () -> service.issue(5000L, 2L, 0, "https://panel.example", "secret"));
        time.addAndGet(NodeInstallBootstrapService.LIFETIME.toMillis());
        assertNotNull(service.issue(5000L, 2L, 0, "https://panel.example", "secret"));
    }
}

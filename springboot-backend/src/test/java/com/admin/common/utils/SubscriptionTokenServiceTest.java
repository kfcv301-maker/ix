package com.admin.common.utils;

import com.admin.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionTokenServiceTest {

    @Test
    void tokenIsScopedAndRevokedByAccountVersion() {
        SubscriptionTokenService service = new SubscriptionTokenService("test-only-secret");
        User user = new User();
        user.setId(42L);
        user.setTokenVersion(0);
        String token = service.issue(user);
        assertTrue(service.matches(user, token));
        assertFalse(service.matches(user, "invalid"));
        user.setTokenVersion(1);
        assertFalse(service.matches(user, token));
        assertNotEquals(token, service.issue(user));
    }
}

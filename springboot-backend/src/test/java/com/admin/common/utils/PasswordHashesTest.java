package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHashesTest {

    @Test
    void newHashesAreSaltedAndVerify() {
        String first = PasswordHashes.encode("correct horse battery staple");
        String second = PasswordHashes.encode("correct horse battery staple");
        assertNotEquals(first, second);
        assertTrue(PasswordHashes.matches("correct horse battery staple", first));
        assertFalse(PasswordHashes.matches("wrong", first));
        assertFalse(PasswordHashes.isLegacyMd5(first));
        String longPassword = "very-long-password-".repeat(12);
        assertTrue(PasswordHashes.matches(longPassword, PasswordHashes.encode(longPassword)));
    }

    @Test
    void legacyMd5IsAcceptedOnlyForCorrectPassword() {
        String legacy = Md5Util.md5("legacy secret");
        assertTrue(PasswordHashes.isLegacyMd5(legacy));
        assertTrue(PasswordHashes.matches("legacy secret", legacy));
        assertFalse(PasswordHashes.matches("incorrect", legacy));
        assertFalse(PasswordHashes.matches("legacy secret", "garbage"));
    }
}

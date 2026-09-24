package com.admin.common.utils;

import com.admin.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** A read-only subscription credential, revoked when the account password changes. */
@Component
public class SubscriptionTokenService {

    private final byte[] secret;

    public SubscriptionTokenService(@Value("${jwt-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(User user) {
        if (user == null || user.getId() == null) throw new IllegalArgumentException("用户不存在");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
            String message = "subscription:v1:" + user.getId() + ":" + version;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成订阅令牌", exception);
        }
    }

    public boolean matches(User user, String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        return MessageDigest.isEqual(issue(user).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }
}

package com.admin.common.utils;

import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSON;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * JWT工具类，不使用第三方库实现
 */
@Component
public class JwtUtil {
    
    @Value("${jwt-secret}")
    private String secretKey;

    @Resource
    private UserMapper userMapper;
    
    private static String SECRET_KEY;
    private static UserMapper USER_MAPPER;
    
    // token有效期，90天；密码变更会通过 token_version 立即使旧令牌失效。
    private static final long EXPIRE_TIME = 90L * 24 * 60 * 60 * 1000;
    // 算法
    private static final String ALGORITHM = "HmacSHA256";

    @PostConstruct
    public void init() {
        SECRET_KEY = this.secretKey;
        USER_MAPPER = this.userMapper;
    }

    /**
     * 生成JWT Token
     *
     * @param user 用户信息
     * @return 生成的JWT Token
     */
    public static String generateToken(User user) {
        try {
            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            Date expireDate = new Date(nowMillis + EXPIRE_TIME);

            // Header
            Map<String, Object> header = new HashMap<>();
            header.put("alg", ALGORITHM);
            header.put("typ", "JWT");
            String headerJson = JSON.toJSONString(header);
            String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

            // Payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", user.getId().toString());
            payload.put("iat", now.getTime() / 1000); // 发布时间
            payload.put("exp", expireDate.getTime() / 1000); // 过期时间
            payload.put("user", user.getUser());
            payload.put("name", user.getUser());
            payload.put("role_id", user.getRoleId());
            payload.put("token_version", currentTokenVersion(user));

            String payloadJson = JSON.toJSONString(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            // Signature
            String signature = calculateSignature(encodedHeader, encodedPayload);

            // Token
            return encodedHeader + "." + encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("JWT token generation failed", e);
        }
    }

    /**
     * 验证JWT Token
     *
     * @param token JWT Token
     * @return 验证是否通过
     */
    public static boolean validateToken(String token) {
        try {
            if (token == null || token.isEmpty()) {
                return false;
            }

            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String encodedHeader = parts[0];
            String encodedPayload = parts[1];
            String signature = parts[2];

            // 验证签名
            String expectedSignature = calculateSignature(encodedHeader, encodedPayload);
            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII))) {
                return false;
            }

            // 验证过期时间
            String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
            long exp = Long.parseLong(payload.get("exp").toString());
            long now = System.currentTimeMillis() / 1000;
            
            return exp > now && isCurrentUserToken(payload);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从JWT Token中获取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public static Long getUserIdFromToken(String token) {
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Long.parseLong(payload.get("sub").toString());
    }


    public static Integer getUserIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("sub").toString());
    }

    public static String getNameFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return payload.get("name").toString();
    }

    /**
     * 从JWT Token中获取用户角色ID
     *
     * @param token JWT Token
     * @return 角色ID
     */
    public static Integer getRoleIdFromToken(String token) {
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("role_id").toString());
    }

    @SneakyThrows
    public static Integer getRoleIdFromToken() {
        String token = HttpContextUtils.getHttpServletRequest().getHeader("Authorization");
        if (token == null || token.isEmpty()) throw new Exception();
        String[] parts = token.split("\\.");
        String encodedPayload = parts[1];
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        Map<String, Object> payload = JSON.parseObject(decodedPayload, Map.class);
        return Integer.parseInt(payload.get("role_id").toString());
    }
    /**
     * 计算签名
     *
     * @param encodedHeader  编码后的头部
     * @param encodedPayload 编码后的负载
     * @return 签名
     * @throws Exception 签名计算异常
     */
    private static String calculateSignature(String encodedHeader, String encodedPayload) throws Exception {
        String content = encodedHeader + "." + encodedPayload;
        Mac hmac = Mac.getInstance(ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        hmac.init(secretKeySpec);
        byte[] signatureBytes = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }

    /**
     * Tokens are stateless for normal request processing, but password changes
     * need immediate and durable revocation. The stored version is checked only
     * after signature and expiry validation, so a prior token cannot survive a
     * password update or a disabled account.
     */
    private static boolean isCurrentUserToken(Map<String, Object> payload) {
        if (USER_MAPPER == null || payload.get("sub") == null || payload.get("token_version") == null
                || payload.get("role_id") == null) {
            return false;
        }
        try {
            Long userId = Long.parseLong(payload.get("sub").toString());
            int tokenVersion = Integer.parseInt(payload.get("token_version").toString());
            int tokenRoleId = Integer.parseInt(payload.get("role_id").toString());
            User user = USER_MAPPER.selectById(userId);
            return user != null
                    && Objects.equals(user.getStatus(), 1)
                    && Objects.equals(user.getRoleId(), tokenRoleId)
                    && currentTokenVersion(user) == tokenVersion;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int currentTokenVersion(User user) {
        return user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }
}

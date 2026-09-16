package com.zys.backend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spring → 修图 Agent 方向的短时 HMAC 服务令牌签发。
 * 与 Agent 侧 app/service_token.py 的规范保持一致：
 * base64url(canonical_json) + "." + base64url(hmac_sha256(secret, canonical_json))
 */
@Slf4j
public final class AgentServiceTokenIssuer {

    /**
     * Spring→Agent 方向受众
     */
    public static final String GALLERY_AUDIENCE = "retouch-agent";

    /**
     * Agent→Spring 方向受众
     */
    public static final String AGENT_AUDIENCE = "cloud-gallery";

    /**
     * 素材访问令牌受众
     */
    public static final String ASSET_AUDIENCE = "agent-asset";

    /**
     * 服务令牌有效期上限（秒）
     */
    public static final int MAX_TTL_SECONDS = 300;

    /**
     * 素材访问令牌有效期上限（秒）
     */
    public static final int MAX_ASSET_TTL_SECONDS = 24 * 3600;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentServiceTokenIssuer() {
    }

    /**
     * 签发 Spring→Agent 服务令牌
     *
     * @param secret 共享密钥（不少于 32 字节）
     */
    public static String issueToken(String secret, AgentCallContext context) {
        return issueTokenWithAudience(secret, context, GALLERY_AUDIENCE, MAX_TTL_SECONDS);
    }

    /**
     * 按指定受众与有效期签发服务令牌
     */
    public static String issueTokenWithAudience(String secret, AgentCallContext context,
                                                String audience, int ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("服务令牌有效期必须在 1~" + MAX_TTL_SECONDS + " 秒之间");
        }
        requireSecret(secret);
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> body = new TreeMap<>();
        body.put("uid", String.valueOf(context.getUserId()));
        if (context.getPictureId() != null) {
            body.put("pic", String.valueOf(context.getPictureId()));
        }
        if (context.getSpaceId() != null) {
            body.put("spc", String.valueOf(context.getSpaceId()));
        }
        if (context.getPermissions() != null) {
            body.put("perm", context.getPermissions());
        }
        if (context.getRequestId() != null) {
            body.put("rid", context.getRequestId());
        }
        body.put("aud", audience);
        body.put("iat", now);
        body.put("exp", now + ttlSeconds);
        body.put("nonce", randomHex());
        try {
            String raw = JSON.writeValueAsString(body);
            return b64Url(raw.getBytes(StandardCharsets.UTF_8)) + "." + hmacSha256(secret, raw);
        } catch (Exception e) {
            throw new IllegalStateException("签发服务令牌失败", e);
        }
    }

    /**
     * 签发素材访问令牌（浏览器经云图库网关查看 Agent 素材用）
     */
    public static String issueObjectToken(String objectKey, String secret, int ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > MAX_ASSET_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "素材访问令牌有效期必须在 1~" + MAX_ASSET_TTL_SECONDS + " 秒之间");
        }
        requireSecret(secret);
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> body = new TreeMap<>();
        body.put("obj", objectKey);
        body.put("aud", ASSET_AUDIENCE);
        body.put("iat", now);
        body.put("exp", now + ttlSeconds);
        body.put("nonce", randomHex());
        try {
            String raw = JSON.writeValueAsString(body);
            return b64Url(raw.getBytes(StandardCharsets.UTF_8)) + "." + hmacSha256(secret, raw);
        } catch (Exception e) {
            throw new IllegalStateException("签发素材访问令牌失败", e);
        }
    }

    private static void requireSecret(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("修图 Agent 服务密钥未配置或短于 32 字节");
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String hmacSha256(String secret, String raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64Url(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}

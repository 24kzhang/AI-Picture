package com.zys.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Agent → Spring 方向服务令牌校验，以及素材访问令牌解析。
 * 与 Agent 侧 app/service_token.py 的规范保持一致。
 */
@Slf4j
public final class AgentServiceTokenVerifier {

    /**
     * Agent→Spring 方向受众
     */
    public static final String AGENT_AUDIENCE = "cloud-gallery";

    /**
     * 素材访问令牌受众
     */
    public static final String ASSET_AUDIENCE = "agent-asset";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CLOCK_SKEW_SECONDS = 10;

    private AgentServiceTokenVerifier() {
    }

    /**
     * 校验服务令牌（Agent→Spring 方向），通过返回负载，失败返回 null
     */
    public static JsonNode verifyServiceToken(String token, String secret) {
        return verify(token, secret, AGENT_AUDIENCE, 300);
    }

    /**
     * 解析素材访问令牌，通过返回对象键，失败返回 null
     */
    public static String readAssetToken(String token, String secret) {
        JsonNode body = verify(token, secret, ASSET_AUDIENCE, 24 * 3600);
        if (body == null) {
            return null;
        }
        String objectKey = body.path("obj").asText(null);
        if (objectKey == null || objectKey.isEmpty()) {
            return null;
        }
        return objectKey;
    }

    private static JsonNode verify(String token, String secret, String audience, int maxTtlSeconds) {
        if (token == null || secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            return null;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return null;
            }
            byte[] raw = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(signature, mac.doFinal(raw))) {
                return null;
            }
            JsonNode body = JSON.readTree(raw);
            if (!audience.equals(body.path("aud").asText())) {
                return null;
            }
            long iat = body.path("iat").asLong();
            long exp = body.path("exp").asLong();
            long now = System.currentTimeMillis() / 1000;
            if (exp < now || iat > now + CLOCK_SKEW_SECONDS) {
                return null;
            }
            if (exp - iat > maxTtlSeconds) {
                return null;
            }
            return body;
        } catch (Exception e) {
            log.debug("服务令牌校验异常：{}", e.getMessage());
            return null;
        }
    }
}

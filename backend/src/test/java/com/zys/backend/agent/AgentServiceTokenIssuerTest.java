package com.zys.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务令牌签发测试：结构、HMAC 签名与有效期约束
 */
class AgentServiceTokenIssuerTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldIssueVerifiableToken() throws Exception {
        AgentCallContext context = AgentCallContext.builder()
                .userId(1234567890123456789L)
                .pictureId(987654321L)
                .spaceId(1002L)
                .permissions(Collections.singletonList("picture:edit"))
                .requestId("req-001")
                .build();

        String token = AgentServiceTokenIssuer.issueToken(SECRET, context);

        String[] parts = token.split("\\.");
        assertEquals(2, parts.length);
        byte[] raw = Base64.getUrlDecoder().decode(parts[0]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);

        // HMAC 签名可复算
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertTrue(Arrays.equals(signature, mac.doFinal(raw)));

        JsonNode body = mapper.readTree(raw);
        assertEquals("1234567890123456789", body.path("uid").asText());
        assertEquals("987654321", body.path("pic").asText());
        assertEquals("1002", body.path("spc").asText());
        assertEquals("retouch-agent", body.path("aud").asText());
        assertEquals("req-001", body.path("rid").asText());
        assertTrue(body.has("nonce"));
        long iat = body.path("iat").asLong();
        long exp = body.path("exp").asLong();
        assertTrue(exp - iat > 0 && exp - iat <= AgentServiceTokenIssuer.MAX_TTL_SECONDS);
        assertNotNull(body.path("perm"));
    }

    @Test
    void shouldRejectShortSecret() {
        AgentCallContext context = AgentCallContext.builder().userId(1L).build();
        assertThrows(IllegalArgumentException.class,
                () -> AgentServiceTokenIssuer.issueToken("short", context));
        assertThrows(IllegalArgumentException.class,
                () -> AgentServiceTokenIssuer.issueToken(null, context));
    }
}

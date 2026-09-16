package com.zys.backend.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务令牌校验测试（Agent→Spring 方向与素材访问令牌）
 */
class AgentServiceTokenVerifierTest {

    private static final String SECRET = "verifier-test-secret-0123456789-0123456789";

    private String serviceToken() {
        AgentCallContext context = AgentCallContext.builder()
                .userId(0L)
                .build();
        // 复用签发器构造 AGENT_AUDIENCE 令牌
        return issueWithAudience(context, AgentServiceTokenVerifier.AGENT_AUDIENCE);
    }

    private String issueWithAudience(AgentCallContext context, String audience) {
        return AgentServiceTokenIssuer.issueTokenWithAudience(SECRET, context, audience, 300);
    }

    @Test
    void validServiceTokenPasses() {
        JsonNode payload = AgentServiceTokenVerifier.verifyServiceToken(serviceToken(), SECRET);
        assertNotNull(payload);
        assertEquals("0", payload.path("uid").asText());
        assertEquals(AgentServiceTokenVerifier.AGENT_AUDIENCE, payload.path("aud").asText());
    }

    @Test
    void tamperedTokenRejected() {
        String token = serviceToken();
        String forged = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertNull(AgentServiceTokenVerifier.verifyServiceToken(forged, SECRET));
    }

    @Test
    void wrongSecretRejected() {
        assertNull(AgentServiceTokenVerifier.verifyServiceToken(
                serviceToken(), "another-secret-0123456789-0123456789-xy"));
    }

    @Test
    void wrongAudienceRejected() {
        String galleryToken = issueWithAudience(
                AgentCallContext.builder().userId(1L).build(),
                AgentServiceTokenIssuer.GALLERY_AUDIENCE);
        assertNull(AgentServiceTokenVerifier.verifyServiceToken(galleryToken, SECRET));
    }

    @Test
    void malformedTokenRejected() {
        assertNull(AgentServiceTokenVerifier.verifyServiceToken(null, SECRET));
        assertNull(AgentServiceTokenVerifier.verifyServiceToken("garbage", SECRET));
        assertNull(AgentServiceTokenVerifier.verifyServiceToken("a.b.c", SECRET));
        assertNull(AgentServiceTokenVerifier.verifyServiceToken("", SECRET));
    }

    @Test
    void shortSecretRejected() {
        assertNull(AgentServiceTokenVerifier.verifyServiceToken(serviceToken(), "short"));
    }

    @Test
    void assetTokenRoundTrip() {
        String token = AgentServiceTokenIssuer.issueObjectToken(
                "agent-temp/u1/a1.png", SECRET, 3600);
        assertEquals("agent-temp/u1/a1.png",
                AgentServiceTokenVerifier.readAssetToken(token, SECRET));
    }

    @Test
    void assetTokenForgedRejected() {
        String token = AgentServiceTokenIssuer.issueObjectToken("agent-temp/u1/a1.png", SECRET, 3600);
        String forged = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertNull(AgentServiceTokenVerifier.readAssetToken(forged, SECRET));
        assertNull(AgentServiceTokenVerifier.readAssetToken(serviceToken(), SECRET));
    }

    @Test
    void assetTokenRejectsOverlongTtl() {
        assertTrue(AgentServiceTokenIssuer.issueObjectToken("k", SECRET, 24 * 3600).length() > 0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentServiceTokenIssuer.issueObjectToken("k", SECRET, 24 * 3600 + 1));
    }
}

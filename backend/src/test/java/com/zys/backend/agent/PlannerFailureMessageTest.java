package com.zys.backend.agent;

import com.zys.backend.agent.service.PlannerFailureMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerFailureMessageTest {
    @Test
    void identifiesActualBillingFailureInsteadOfSuggestingBlindRetry() {
        String message = PlannerFailureMessage.describe(400,
                "{\"error\":{\"code\":\"Arrearage\",\"message\":\"private provider details\"}}");
        assertTrue(message.contains("欠费"));
        assertTrue(message.contains("充值"));
        assertFalse(message.contains("private provider details"));
    }

    @Test
    void handlesCredentialsThrottlingAndNonJsonGateways() {
        assertTrue(PlannerFailureMessage.describe(401, "{}").contains("密钥无效"));
        assertTrue(PlannerFailureMessage.describe(403, "{}").contains("权限"));
        assertTrue(PlannerFailureMessage.describe(429, "{}").contains("请求受限"));
        assertTrue(PlannerFailureMessage.describe(502, "<html>secret</html>").contains("HTTP 502"));
        assertFalse(PlannerFailureMessage.describe(502, "<html>secret</html>").contains("secret"));
    }
}

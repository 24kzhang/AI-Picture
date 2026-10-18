package com.zys.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestWrapperTest {
    @Test
    void keepsUtf8BodyReadableAfterRepeatedReads() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json;charset=UTF-8");
        request.setContent("{\"名称\":\"海滩\"}".getBytes(StandardCharsets.UTF_8));
        RequestWrapper wrapper = new RequestWrapper(request);
        assertEquals("{\"名称\":\"海滩\"}", wrapper.getReader().readLine());
        assertEquals("{\"名称\":\"海滩\"}", new String(wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}

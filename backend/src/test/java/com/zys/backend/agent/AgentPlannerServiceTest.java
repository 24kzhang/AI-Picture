package com.zys.backend.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.zys.backend.agent.model.PlannerOutcome;
import com.zys.backend.agent.service.AgentPlannerService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.service.SelectionService;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.model.entity.PictureEditSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentPlannerServiceTest {
    @Test void selfHostedToolsWorkWithoutAliyunKeyAndRejectTruncatedCalls() throws Exception {
        AtomicReference<JSONObject> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>("tool_calls");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024]; int count;
            while ((count = exchange.getRequestBody().read(chunk)) != -1) buffer.write(chunk, 0, count);
            received.set(JSONUtil.parseObj(new String(buffer.toByteArray(), StandardCharsets.UTF_8)));
            JSONObject call = new JSONObject().set("id", "call_title").set("type", "function")
                    .set("function", new JSONObject().set("name", "add_text_layer").set("arguments", "{\"text\":\"每日现磨\"}"));
            JSONObject message = new JSONObject().set("role", "assistant").set("content", "准备添加标题")
                    .set("tool_calls", new JSONArray().put(call));
            byte[] response = new JSONObject().set("choices", new JSONArray().put(new JSONObject()
                    .set("finish_reason", reason.get()).set("message", message))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            AgentPlannerService service = new AgentPlannerService();
            ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
            ReflectionTestUtils.setField(service, "apiKey", "");
            ReflectionTestUtils.setField(service, "model", "Qwen3.8-27B-FP8");
            ReflectionTestUtils.setField(service, "timeoutMs", 10000);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.toAgentToolSchemas()).thenReturn(new JSONArray());
            ReflectionTestUtils.setField(service, "toolRegistry", registry);
            ReflectionTestUtils.setField(service, "layerDocumentService", mock(LayerDocumentService.class));
            ReflectionTestUtils.setField(service, "selectionService", mock(SelectionService.class));
            assertTrue(service.available());
            PlannerOutcome outcome = service.plan(new PictureEditSession(), null, "添加咖啡标题");
            assertEquals("add_text_layer", outcome.getRawSteps().get(0).get("tool"));
            assertNull(authorization.get());
            assertEquals("Qwen3.8-27B-FP8", received.get().getStr("model"));
            assertFalse(received.get().getBool("stream"));
            assertFalse(received.get().getBool("enable_thinking"));
            assertNotNull(received.get().getJSONArray("tools"));
            String firstId = received.get().getStr("request_id");
            reason.set("length");
            outcome = service.plan(new PictureEditSession(), null, "添加咖啡标题");
            assertTrue(outcome.getRawSteps().isEmpty());
            assertTrue(outcome.getReply().contains("未完整生成"));
            assertNotEquals(firstId, received.get().getStr("request_id"));
        } finally { server.stop(0); }
    }
}

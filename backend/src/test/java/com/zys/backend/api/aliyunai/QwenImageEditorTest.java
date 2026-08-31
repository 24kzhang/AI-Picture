package com.zys.backend.api.aliyunai;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 只连接本机模拟服务，验证千问协议适配，不消耗模型额度。 */
class QwenImageEditorTest {
    private HttpServer server;
    private QwenImageEditor editor;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private int status = 200;
    private String body = "{\"request_id\":\"test-request\",\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://example.com/result.png\"}]}}]}}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/edit", exchange -> {
            try (Scanner scanner = new Scanner(exchange.getRequestBody(), "UTF-8")) {
                request.set(scanner.useDelimiter("\\A").next());
            }
            asyncHeader.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        editor = new QwenImageEditor();
        ReflectionTestUtils.setField(editor, "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/edit");
    }

    @AfterEach
    void tearDown() {
        editor.close();
        server.stop(0);
    }

    private GetOutPaintingTaskResponse runTask() throws Exception {
        String id = editor.submit("test-key", "qwen-image-2.0-pro", "https://example.com/source.png", "替换为日落海滩").getOutput().getTaskId();
        assertTrue(id.startsWith(QwenImageEditor.TASK_PREFIX));
        for (int i = 0; i < 200; i++) {
            GetOutPaintingTaskResponse response = editor.getTask(id);
            if (!"PENDING".equals(response.getOutput().getTaskStatus())
                    && !"RUNNING".equals(response.getOutput().getTaskStatus())) return response;
            Thread.sleep(20);
        }
        fail("本机测试任务未结束");
        return null;
    }

    @Test
    void preservesPromptAndAdaptsSuccessfulResult() throws Exception {
        GetOutPaintingTaskResponse result = runTask();
        assertEquals("SUCCEEDED", result.getOutput().getTaskStatus());
        assertEquals("https://example.com/result.png", result.getOutput().getResults().get(0).getUrl());
        assertEquals("test-request", result.getRequestId());
        JSONObject sent = JSONUtil.parseObj(request.get());
        assertEquals("qwen-image-2.0-pro", sent.getStr("model"));
        JSONObject message = sent.getJSONObject("input").getJSONArray("messages").getJSONObject(0);
        assertEquals("https://example.com/source.png", message.getJSONArray("content").getJSONObject(0).getStr("image"));
        assertEquals("替换为日落海滩", message.getJSONArray("content").getJSONObject(1).getStr("text"));
        assertFalse(sent.getJSONObject("parameters").getBool("prompt_extend"));
        assertFalse(sent.getJSONObject("parameters").containsKey("strength"));
        assertNull(asyncHeader.get());
    }

    @Test
    void exposesFailedTasksWithoutLeakingProviderMessage() throws Exception {
        status = 403;
        body = "{\"code\":\"AccessDenied\",\"message\":\"private-provider-detail\"}";
        GetOutPaintingTaskResponse result = runTask();
        assertEquals("FAILED", result.getOutput().getTaskStatus());
        assertTrue(result.getOutput().getMessage().contains("AccessDenied"));
        assertFalse(result.getOutput().getMessage().contains("private-provider-detail"));
    }

    @Test
    void rejectsMissingImage() throws Exception {
        body = "{\"output\":{\"choices\":[]}}";
        assertEquals("FAILED", runTask().getOutput().getTaskStatus());
    }

    @Test
    void rejectsMalformedResponse() throws Exception {
        body = "服务暂不可用";
        assertEquals("FAILED", runTask().getOutput().getTaskStatus());
    }

    @Test
    void missingTaskDoesNotQueryCloud() {
        assertEquals("UNKNOWN", editor.getTask("qwen-local-missing").getOutput().getTaskStatus());
        assertNull(request.get());
    }
}

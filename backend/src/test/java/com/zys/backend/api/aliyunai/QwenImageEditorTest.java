package com.zys.backend.api.aliyunai;

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
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只连接本机模拟服务，验证千问协议适配，不消耗模型额度。 */
class QwenImageEditorTest {
    private HttpServer server;
    private QwenImageEditor editor;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> asyncHeader = new AtomicReference<>();
    private int status = 200;
    private String body = "{\"url\":\"/qwen-image/outputs/result.png\"}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qwen-image/api/edit", exchange -> {
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
        server.createContext("/source", exchange -> {
            byte[] bytes = "PNG".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        editor = new QwenImageEditor();
        ReflectionTestUtils.setField(editor, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        editor.close();
        server.stop(0);
    }

    private GetOutPaintingTaskResponse runTask() throws Exception {
        return runTask(true);
    }

    private GetOutPaintingTaskResponse runTask(boolean keepAlpha) throws Exception {
        String id = editor.submit("http://127.0.0.1:" + server.getAddress().getPort() + "/source", "替换为日落海滩", null, keepAlpha).getOutput().getTaskId();
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
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/qwen-image/outputs/result.png", result.getOutput().getResults().get(0).getUrl());
        assertNull(result.getRequestId());
        assertTrue(request.get().contains("替换为日落海滩"));
        assertNull(asyncHeader.get());
        assertTrue(request.get().contains("name=\"keep_alpha\"\r\n\r\ntrue"));
        assertTrue(editor.isGeneratedOutputUrl(result.getOutput().getOutputImageUrl()));
        assertFalse(editor.isGeneratedOutputUrl(result.getOutput().getOutputImageUrl() + "?redirect=1"));
        assertFalse(editor.isGeneratedOutputUrl("http://127.0.0.1/secret"));
    }

    @Test
    void allowsModelToRebuildAlphaForTransparentOutput() throws Exception {
        assertEquals("SUCCEEDED", runTask(false).getOutput().getTaskStatus());
        assertTrue(request.get().contains("name=\"keep_alpha\"\r\n\r\nfalse"));
    }

    @Test
    void exposesFailedTasksWithoutLeakingProviderMessage() throws Exception {
        status = 403;
        body = "{\"code\":\"AccessDenied\",\"message\":\"private-provider-detail\"}";
        GetOutPaintingTaskResponse result = runTask();
        assertEquals("FAILED", result.getOutput().getTaskStatus());
        assertTrue(result.getOutput().getMessage().contains("HTTP 403"));
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

    @Test
    void rejectsPollingByAnotherUser() {
        String id = editor.submit("http://127.0.0.1/source", "测试", null, true, 10L)
                .getOutput().getTaskId();
        GetOutPaintingTaskResponse result = editor.getTask(id, 11L);
        assertEquals("UNKNOWN", result.getOutput().getTaskStatus());
        assertEquals("任务不存在或无权访问", result.getOutput().getMessage());
        assertEquals("PENDING", editor.getTask(id, 10L).getOutput().getTaskStatus());
    }

    @Test
    void generatedOutputRequiresOwnerAndSourcePicture() {
        @SuppressWarnings("unchecked") org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        ReflectionTestUtils.setField(editor, "jdbc", jdbc);
        when(jdbc.queryForList(anyString(), eq("https://model/output.png"))).thenReturn(List.of(
                Map.of("owner_id", 10L, "source_url", "https://gallery/source.png", "status", "SUCCEEDED")));
        assertTrue(editor.isGeneratedOutputUrl("https://model/output.png", 10L, "https://gallery/source.png"));
        assertFalse(editor.isGeneratedOutputUrl("https://model/output.png", 11L, "https://gallery/source.png"));
        assertFalse(editor.isGeneratedOutputUrl("https://model/output.png", 10L, "https://gallery/other.png"));
    }
}

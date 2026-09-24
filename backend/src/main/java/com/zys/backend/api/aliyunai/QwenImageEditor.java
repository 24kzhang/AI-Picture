package com.zys.backend.api.aliyunai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 将千问同步编辑接口适配为前端现有的任务轮询协议，避免长请求阻塞网页。
 * 任务仅保存在本机内存中，重启后失效；生成结果需在有效期内保存到图库。
 */
@Slf4j
@Component
public class QwenImageEditor {
    static final String TASK_PREFIX = "qwen-local-";

    @Value("${qwenImage.baseUrl:http://172.18.20.44:30066}")
    private String baseUrl;

    private final Cache<String, GetOutPaintingTaskResponse> tasks = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(24, TimeUnit.HOURS).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "qwen-image-edit");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public CreateOutPaintingTaskResponse submit(String imageUrl, String prompt) {
        String taskId = TASK_PREFIX + UUID.randomUUID();
        tasks.put(taskId, state(taskId, "PENDING"));
        try {
            executor.execute(() -> generate(taskId, imageUrl, prompt));
        } catch (RejectedExecutionException e) {
            tasks.invalidate(taskId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 编辑任务较多，请稍后重试");
        }
        CreateOutPaintingTaskResponse response = new CreateOutPaintingTaskResponse();
        CreateOutPaintingTaskResponse.Output output = new CreateOutPaintingTaskResponse.Output();
        output.setTaskId(taskId);
        output.setTaskStatus("PENDING");
        response.setOutput(output);
        return response;
    }

    public GetOutPaintingTaskResponse getTask(String taskId) {
        GetOutPaintingTaskResponse response = tasks.getIfPresent(taskId);
        if (response != null) {
            return response;
        }
        response = state(taskId, "UNKNOWN");
        response.getOutput().setMessage("任务已过期或服务已重启，请重新生成");
        return response;
    }

    private void generate(String taskId, String imageUrl, String prompt) {
        tasks.put(taskId, state(taskId, "RUNNING"));
        try {
            byte[] source;
            try (HttpResponse input = HttpRequest.get(imageUrl).timeout(30000).execute()) {
                if (!input.isOk()) {
                    fail(taskId, "读取原图失败（HTTP " + input.getStatus() + "）");
                    return;
                }
                source = input.bodyBytes();
            }
            try (HttpResponse http = HttpRequest.post(baseUrl + "/qwen-image/api/edit")
                    .form("image", source, "input.png")
                    .form("prompt", prompt)
                    .form("steps", 30)
                    .form("guidance_scale", 4)
                    .timeout(1800000).execute()) {
                if (!http.isOk()) {
                    log.warn("Qwen Image 编辑失败，状态码={}", http.getStatus());
                    fail(taskId, "模型请求失败（HTTP " + http.getStatus() + "）");
                    return;
                }
                JSONObject result = JSONUtil.parseObj(http.body());
                String path = result.getStr("url");
                if (StrUtil.isBlank(path) || !path.matches("/qwen-image/outputs/[A-Za-z0-9_-]+\\.png")) {
                    fail(taskId, "模型未返回有效图片地址");
                    return;
                }
                String url = baseUrl.replaceAll("/+$", "") + path;
                GetOutPaintingTaskResponse response = state(taskId, "SUCCEEDED");
                GetOutPaintingTaskResponse.Result image = new GetOutPaintingTaskResponse.Result();
                image.setUrl(url);
                response.getOutput().setResults(Collections.singletonList(image));
                response.getOutput().setOutputImageUrl(url);
                response.setRequestId(result.getStr("id"));
                tasks.put(taskId, response);
            }
        } catch (Exception e) {
            log.warn("千问编辑请求未完成，异常类型={}", e.getClass().getSimpleName());
            fail(taskId, "模型连接失败或等待超时，请稍后重试");
        }
    }

    private GetOutPaintingTaskResponse state(String taskId, String status) {
        GetOutPaintingTaskResponse response = new GetOutPaintingTaskResponse();
        GetOutPaintingTaskResponse.Output output = new GetOutPaintingTaskResponse.Output();
        output.setTaskId(taskId);
        output.setTaskStatus(status);
        response.setOutput(output);
        return response;
    }

    private void fail(String taskId, String message) {
        GetOutPaintingTaskResponse response = state(taskId, "FAILED");
        response.getOutput().setMessage(message);
        tasks.put(taskId, response);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}

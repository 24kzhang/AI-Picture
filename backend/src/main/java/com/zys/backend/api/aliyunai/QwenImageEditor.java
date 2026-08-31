package com.zys.backend.api.aliyunai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
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

    @Value("${aliYunAi.imageEditUrl:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}")
    private String endpoint;

    private final Cache<String, GetOutPaintingTaskResponse> tasks = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(24, TimeUnit.HOURS).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "qwen-image-edit");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public CreateOutPaintingTaskResponse submit(String apiKey, String model, String imageUrl, String prompt) {
        String taskId = TASK_PREFIX + UUID.randomUUID();
        tasks.put(taskId, state(taskId, "PENDING"));
        try {
            executor.execute(() -> generate(taskId, apiKey, model, imageUrl, prompt));
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

    private void generate(String taskId, String apiKey, String model, String imageUrl, String prompt) {
        tasks.put(taskId, state(taskId, "RUNNING"));
        // 千问不接受旧版万相的 strength 参数，直接遵循原始指令，不做自动改写。
        JSONObject message = JSONUtil.createObj().set("role", "user").set("content", JSONUtil.createArray()
                .put(JSONUtil.createObj().set("image", imageUrl))
                .put(JSONUtil.createObj().set("text", prompt)));
        JSONObject body = JSONUtil.createObj().set("model", model)
                .set("input", JSONUtil.createObj().set("messages", JSONUtil.createArray().put(message)))
                .set("parameters", JSONUtil.createObj().set("n", 1).set("watermark", false)
                        .set("prompt_extend", false));
        try (HttpResponse http = HttpRequest.post(endpoint)
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .timeout(180000).body(body.toString()).execute()) {
            JSONObject result = JSONUtil.parseObj(http.body());
            if (!http.isOk() || StrUtil.isNotBlank(result.getStr("code"))) {
                // 不记录密钥、完整请求或带签名的图片链接。
                log.warn("千问编辑失败，状态码={}，错误码={}，请求标识={}",
                        http.getStatus(), result.getStr("code"), result.getStr("request_id"));
                fail(taskId, "模型请求失败，请检查模型权限、额度和密钥（"
                        + result.getStr("code", "HTTP " + http.getStatus()) + "）");
                return;
            }
            String url = imageUrl(result);
            if (StrUtil.isBlank(url)) {
                fail(taskId, "模型未返回可用图片，请重新生成");
                return;
            }
            GetOutPaintingTaskResponse response = state(taskId, "SUCCEEDED");
            GetOutPaintingTaskResponse.Result image = new GetOutPaintingTaskResponse.Result();
            image.setUrl(url);
            response.getOutput().setResults(Collections.singletonList(image));
            response.getOutput().setOutputImageUrl(url);
            response.setRequestId(result.getStr("request_id"));
            tasks.put(taskId, response);
        } catch (Exception e) {
            log.warn("千问编辑请求未完成，异常类型={}", e.getClass().getSimpleName());
            fail(taskId, "模型连接失败或等待超时，请稍后重试");
        }
    }

    private String imageUrl(JSONObject result) {
        JSONObject output = result.getJSONObject("output");
        JSONArray choices = output == null ? null : output.getJSONArray("choices");
        if (choices == null) return null;
        for (int i = 0; i < choices.size(); i++) {
            JSONObject message = choices.getJSONObject(i).getJSONObject("message");
            JSONArray content = message == null ? null : message.getJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.size(); j++) {
                String url = content.getJSONObject(j).getStr("image");
                if (StrUtil.isNotBlank(url)) return url;
            }
        }
        return null;
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

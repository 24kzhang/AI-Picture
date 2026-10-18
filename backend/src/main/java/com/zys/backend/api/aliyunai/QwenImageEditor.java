package com.zys.backend.api.aliyunai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zys.backend.agent.tool.impl.AgentImageOps;
import com.zys.backend.agent.provider.OutpaintingInstructions;
import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    @Resource
    private AgentStorageService storageService;

    private final Cache<String, GetOutPaintingTaskResponse> tasks = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(24, TimeUnit.HOURS).build();
    /** 任务归属只用于轮询鉴权，和状态缓存同生命周期。 */
    private final Map<String, Long> taskOwners = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "qwen-image-edit");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public CreateOutPaintingTaskResponse submit(String imageUrl, String prompt) {
        return submit(imageUrl, prompt, null);
    }

    public CreateOutPaintingTaskResponse submit(String imageUrl, String prompt, byte[] mask) {
        return submit(imageUrl, prompt, mask, true);
    }

    public CreateOutPaintingTaskResponse submit(String imageUrl, String prompt, byte[] mask, boolean keepAlpha) {
        return submitTask(taskId -> generate(taskId, imageUrl, prompt, mask, keepAlpha));
    }

    public CreateOutPaintingTaskResponse submit(String imageUrl, String prompt, byte[] mask,
                                                boolean keepAlpha, Long ownerId) {
        return submitTask(taskId -> generate(taskId, imageUrl, prompt, mask, keepAlpha), ownerId);
    }

    public CreateOutPaintingTaskResponse submitOutpaint(String imageUrl, String prompt,
                                                         CreateOutPaintingTaskRequest.Parameters parameters) {
        return submitTask(taskId -> generateOutpaint(taskId, imageUrl, prompt, parameters));
    }

    public CreateOutPaintingTaskResponse submitOutpaint(String imageUrl, String prompt,
                                                         CreateOutPaintingTaskRequest.Parameters parameters,
                                                         Long ownerId) {
        return submitTask(taskId -> generateOutpaint(taskId, imageUrl, prompt, parameters), ownerId);
    }

    private CreateOutPaintingTaskResponse submitTask(Consumer<String> action) {
        return submitTask(action, null);
    }

    private CreateOutPaintingTaskResponse submitTask(Consumer<String> action, Long ownerId) {
        String taskId = TASK_PREFIX + UUID.randomUUID();
        tasks.put(taskId, state(taskId, "PENDING"));
        if (ownerId != null) taskOwners.put(taskId, ownerId);
        try {
            executor.execute(() -> action.accept(taskId));
        } catch (RejectedExecutionException e) {
            tasks.invalidate(taskId);
            taskOwners.remove(taskId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 编辑任务较多，请稍后重试");
        }
        CreateOutPaintingTaskResponse response = new CreateOutPaintingTaskResponse();
        CreateOutPaintingTaskResponse.Output output = new CreateOutPaintingTaskResponse.Output();
        output.setTaskId(taskId);
        output.setTaskStatus("PENDING");
        response.setOutput(output);
        return response;
    }

    /** 兼容旧调用方，密钥和模型参数不再参与服务器接口调用。 */
    public CreateOutPaintingTaskResponse submit(String apiKey, String model, String imageUrl, String prompt) {
        return submit(imageUrl, prompt);
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

    public GetOutPaintingTaskResponse getTask(String taskId, Long ownerId) {
        Long taskOwner = taskOwners.get(taskId);
        if (taskOwner != null && !taskOwner.equals(ownerId)) {
            GetOutPaintingTaskResponse response = state(taskId, "UNKNOWN");
            response.getOutput().setMessage("任务不存在或无权访问");
            return response;
        }
        return getTask(taskId);
    }

    /** 只信任本服务已成功生成的产物，不能用于放行任意内网 URL。 */
    public boolean isGeneratedOutputUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        return tasks.asMap().values().stream().anyMatch(task -> task.getOutput() != null
                && "SUCCEEDED".equals(task.getOutput().getTaskStatus())
                && url.equals(task.getOutput().getOutputImageUrl()));
    }

    private void generate(String taskId, String imageUrl, String prompt, byte[] mask, boolean keepAlpha) {
        tasks.put(taskId, state(taskId, "RUNNING"));
        try {
            byte[] source = downloadSource(imageUrl);
            generateFromBytes(taskId, source, prompt, mask, keepAlpha);
        } catch (Exception e) {
            log.warn("千问编辑请求未完成，异常类型={}", e.getClass().getSimpleName());
            fail(taskId, "模型连接失败或等待超时，请稍后重试");
        }
    }

    private void generateOutpaint(String taskId, String imageUrl, String prompt,
                                  CreateOutPaintingTaskRequest.Parameters parameters) {
        tasks.put(taskId, state(taskId, "RUNNING"));
        try {
            byte[] source = downloadSource(imageUrl);
            java.awt.image.BufferedImage image = AgentImageOps.read(source);
            float xScale = parameters == null || parameters.getXScale() == null ? 2F : parameters.getXScale();
            float yScale = parameters == null || parameters.getYScale() == null ? 2F : parameters.getYScale();
            if (!Float.isFinite(xScale) || !Float.isFinite(yScale)
                    || xScale < 1F || xScale > 3F || yScale < 1F || yScale > 3F) {
                fail(taskId, "扩图比例需在 1 到 3 之间");
                return;
            }
            int width = Math.round(image.getWidth() * xScale);
            int height = Math.round(image.getHeight() * yScale);
            if (width > 8192 || height > 8192 || (long) width * height > 32_000_000L
                    || width == image.getWidth() && height == image.getHeight()) {
                fail(taskId, "扩图尺寸需大于原图，单边不超过 8192 像素且总面积不超过 3200 万像素");
                return;
            }
            byte[] padded = AgentImageOps.padForOutpaint(source, width, height);
            byte[] mask = AgentImageOps.outpaintMask(source, width, height);
            generateFromBytes(taskId, padded, OutpaintingInstructions.build(prompt), mask, true);
        } catch (Exception e) {
            log.warn("千问扩图请求未完成，异常类型={}", e.getClass().getSimpleName());
            fail(taskId, "模型连接失败或等待超时，请稍后重试");
        }
    }

    private byte[] downloadSource(String imageUrl) {
        if (storageService != null && !storageService.isExternalUrl(imageUrl)) {
            return storageService.load(imageUrl);
        }
        try (HttpResponse input = HttpRequest.get(imageUrl).timeout(30000).execute()) {
            if (!input.isOk()) {
                throw new IllegalStateException("读取原图失败（HTTP " + input.getStatus() + "）");
            }
            return input.bodyBytes();
        }
    }

    private void generateFromBytes(String taskId, byte[] source, String prompt, byte[] mask, boolean keepAlpha) {
        try {
            HttpRequest request = HttpRequest.post(baseUrl + "/qwen-image/api/edit")
                    .form("image", source, "input.png")
                    .form("prompt", prompt)
                    .form("steps", 30)
                    .form("guidance_scale", 4)
                    .form("keep_alpha", keepAlpha)
                    .timeout(1800000);
            if (mask != null && mask.length > 0) {
                request.form("mask", mask, "mask.png")
                        .form("mask_feather", 0);
            }
            try (HttpResponse http = request.execute()) {
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
        taskOwners.clear();
    }
}

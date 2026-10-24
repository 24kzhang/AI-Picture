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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    /** 任务元数据与状态使用同样的上限和过期策略，避免归属缓存无界增长。 */
    private final Cache<String, TaskMeta> metadata = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(24, TimeUnit.HOURS).build();
    @Resource
    private JdbcTemplate jdbc;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "qwen-image-edit");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    public void init() {
        if (jdbc != null) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_image_edit_task (task_id VARCHAR(80) PRIMARY KEY, owner_id BIGINT NULL, source_url VARCHAR(1024) NULL, status VARCHAR(24) NOT NULL, result_url VARCHAR(1024) NULL, request_id VARCHAR(120) NULL, message VARCHAR(300) NULL, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_ai_task_result(result_url(512))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

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
        return submitTask(taskId -> generate(taskId, imageUrl, prompt, mask, keepAlpha), ownerId, imageUrl);
    }

    public CreateOutPaintingTaskResponse submitOutpaint(String imageUrl, String prompt,
                                                         CreateOutPaintingTaskRequest.Parameters parameters) {
        return submitTask(taskId -> generateOutpaint(taskId, imageUrl, prompt, parameters));
    }

    public CreateOutPaintingTaskResponse submitOutpaint(String imageUrl, String prompt,
                                                         CreateOutPaintingTaskRequest.Parameters parameters,
                                                         Long ownerId) {
        return submitTask(taskId -> generateOutpaint(taskId, imageUrl, prompt, parameters), ownerId, imageUrl);
    }

    private CreateOutPaintingTaskResponse submitTask(Consumer<String> action) {
        return submitTask(action, null, null);
    }

    private CreateOutPaintingTaskResponse submitTask(Consumer<String> action, Long ownerId) {
        return submitTask(action, ownerId, null);
    }

    private CreateOutPaintingTaskResponse submitTask(Consumer<String> action, Long ownerId, String sourceUrl) {
        String taskId = TASK_PREFIX + UUID.randomUUID();
        metadata.put(taskId, new TaskMeta(ownerId, sourceUrl));
        putState(taskId, state(taskId, "PENDING"));
        try {
            executor.execute(() -> action.accept(taskId));
        } catch (RejectedExecutionException e) {
            tasks.invalidate(taskId);
            metadata.invalidate(taskId);
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
        if (jdbc != null) {
            try {
                return fromRow(jdbc.queryForMap("SELECT * FROM ai_image_edit_task WHERE task_id=?", taskId));
            } catch (Exception ignored) {
                // 查询不到时按未知任务处理，避免泄露数据库错误。
            }
        }
        response = state(taskId, "UNKNOWN");
        response.getOutput().setMessage("任务已过期或服务已重启，请重新生成");
        return response;
    }

    public GetOutPaintingTaskResponse getTask(String taskId, Long ownerId) {
        TaskMeta meta = metadata.getIfPresent(taskId);
        Long taskOwner = meta == null ? ownerFromDatabase(taskId) : meta.ownerId;
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

    public boolean isGeneratedOutputUrl(String url, Long ownerId, String sourceUrl) {
        if (StrUtil.isBlank(url) || ownerId == null || jdbc == null) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner_id,source_url,status FROM ai_image_edit_task WHERE result_url=?", url);
        return rows.stream().anyMatch(row -> ownerId.equals(row.get("owner_id"))
                && "SUCCEEDED".equals(row.get("status"))
                && StrUtil.equals(sourceUrl, (String) row.get("source_url")));
    }

    private void generate(String taskId, String imageUrl, String prompt, byte[] mask, boolean keepAlpha) {
        putState(taskId, state(taskId, "RUNNING"));
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
        putState(taskId, state(taskId, "RUNNING"));
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
                putState(taskId, response);
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
        putState(taskId, response);
    }

    private void putState(String taskId, GetOutPaintingTaskResponse response) {
        tasks.put(taskId, response);
        if (jdbc == null) return;
        TaskMeta meta = metadata.getIfPresent(taskId);
        jdbc.update("INSERT INTO ai_image_edit_task(task_id,owner_id,source_url,status,result_url,request_id,message) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),result_url=VALUES(result_url),request_id=VALUES(request_id),message=VALUES(message),source_url=COALESCE(VALUES(source_url),source_url)",
                taskId, meta == null ? null : meta.ownerId, meta == null ? null : meta.sourceUrl,
                response.getOutput() == null ? "UNKNOWN" : response.getOutput().getTaskStatus(),
                response.getOutput() == null ? null : response.getOutput().getOutputImageUrl(), response.getRequestId(),
                response.getOutput() == null ? null : response.getOutput().getMessage());
    }

    private Long ownerFromDatabase(String taskId) {
        if (jdbc == null) return null;
        try { return jdbc.queryForObject("SELECT owner_id FROM ai_image_edit_task WHERE task_id=?", Long.class, taskId); }
        catch (Exception ignored) { return null; }
    }

    private GetOutPaintingTaskResponse fromRow(Map<String, Object> row) {
        String taskId = String.valueOf(row.get("task_id"));
        GetOutPaintingTaskResponse response = state(taskId, String.valueOf(row.get("status")));
        response.getOutput().setOutputImageUrl((String) row.get("result_url"));
        response.getOutput().setMessage((String) row.get("message"));
        response.setRequestId((String) row.get("request_id"));
        metadata.put(taskId, new TaskMeta(row.get("owner_id") == null ? null : ((Number) row.get("owner_id")).longValue(), (String) row.get("source_url")));
        return response;
    }

    private static class TaskMeta {
        private final Long ownerId;
        private final String sourceUrl;
        private TaskMeta(Long ownerId, String sourceUrl) { this.ownerId = ownerId; this.sourceUrl = sourceUrl; }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        metadata.invalidateAll();
    }
}

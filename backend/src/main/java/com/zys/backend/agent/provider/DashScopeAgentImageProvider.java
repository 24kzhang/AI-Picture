package com.zys.backend.agent.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ProgressReporter;
import com.zys.backend.api.aliyunai.AliYunAiApi;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DashScope 真实图像编辑 Provider：临时上传原图 → 签名短链 → 异步任务 → 轮询 → 下载结果。
 * 模型不接触长期有效的 COS 私密地址。
 */
@Slf4j
@Component("dashScopeAgentImageProvider")
public class DashScopeAgentImageProvider implements AgentImageProvider {

    private static final long POLL_INTERVAL_MS = 3000L;
    private static final long POLL_TIMEOUT_MS = 8 * 60 * 1000L;
    private static final long PRESIGN_SECONDS = 30 * 60L;

    @Resource
    private AliYunAiApi aliYunAiApi;

    @Resource
    private AgentStorageService storageService;

    @Override
    public String name() {
        return "dashscope";
    }

    @Override
    public List<byte[]> edit(EditImageRequest request, ProgressReporter reporter) {
        if (request.getImage() == null || request.getImage().length == 0) {
            throw new AgentToolException("缺少输入图片");
        }
        String imageUrl = publishTempUrl(request.getImage());
        int count = Math.max(1, Math.min(4, request.getCount()));
        List<byte[]> results = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            reportQuietly(reporter, 30 + 50 * index / count, "生成中（" + (index + 1) + "/" + count + "）");
            results.add(runEditTask(imageUrl, request.getPrompt(), request.getStrength(), reporter));
        }
        reportQuietly(reporter, 90, "下载结果");
        return results;
    }

    @Override
    public byte[] upscale(byte[] image, int scale, ProgressReporter reporter) {
        // DashScope 当前接入通道无独立超分任务，使用高质量双线性放大兜底
        reportQuietly(reporter, 40, "提升分辨率");
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                throw new AgentToolException("图片解码失败");
            }
            int factor = scale < 2 ? 2 : Math.min(scale, 4);
            int width = source.getWidth() * factor;
            int height = source.getHeight() * factor;
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(target, "png", output);
            reportQuietly(reporter, 90, "完成");
            return output.toByteArray();
        } catch (AgentToolException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentToolException("超分失败", e);
        }
    }

    private byte[] runEditTask(String imageUrl, String prompt, Double strength, ProgressReporter reporter) {
        CreateOutPaintingTaskResponse createResponse = aliYunAiApi.createImageEditTask(imageUrl, prompt, strength);
        if (createResponse.getOutput() == null || StrUtil.isBlank(createResponse.getOutput().getTaskId())) {
            throw new AgentToolException("AI 编辑任务创建失败");
        }
        String taskId = createResponse.getOutput().getTaskId();
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        int waited = 0;
        while (System.currentTimeMillis() < deadline) {
            sleep(POLL_INTERVAL_MS);
            waited += POLL_INTERVAL_MS;
            // 轮询期间给出缓慢爬升的进度，避免界面停在同一数字
            int progress = 40 + Math.min(45, (int) (waited / POLL_INTERVAL_MS));
            reportQuietly(reporter, progress, "AI 处理中");
            GetOutPaintingTaskResponse response = aliYunAiApi.getOutPaintingTask(taskId);
            GetOutPaintingTaskResponse.Output output = response.getOutput();
            if (output == null) {
                continue;
            }
            String status = output.getTaskStatus();
            if ("SUCCEEDED".equals(status)) {
                List<String> urls = extractUrls(output);
                if (urls.isEmpty()) {
                    throw new AgentToolException("AI 编辑成功但没有返回图片地址");
                }
                return HttpUtil.downloadBytes(urls.get(0));
            }
            if ("FAILED".equals(status) || "UNKNOWN".equals(status)) {
                String message = output.getMessage();
                throw new AgentToolException("AI 编辑失败" + (StrUtil.isBlank(message) ? "" : "：" + message));
            }
        }
        throw new AgentToolException("AI 编辑超时，请稍后重试");
    }

    private List<String> extractUrls(GetOutPaintingTaskResponse.Output output) {
        List<String> urls = new ArrayList<>();
        if (StrUtil.isNotBlank(output.getOutputImageUrl())) {
            urls.add(output.getOutputImageUrl());
        }
        if (output.getResults() != null) {
            for (GetOutPaintingTaskResponse.Result result : output.getResults()) {
                if (result != null && StrUtil.isNotBlank(result.getUrl())) {
                    urls.add(result.getUrl());
                }
            }
        }
        return urls.isEmpty() ? Collections.emptyList() : urls;
    }

    /**
     * 把输入字节临时上传并生成短期签名地址供模型访问
     */
    private String publishTempUrl(byte[] image) {
        com.zys.backend.agent.service.StoredObject stored =
                storageService.storeSessionAsset(0L, "provider-input", "png", image);
        return storageService.presignedUrl(stored.getStorageKey(), PRESIGN_SECONDS);
    }

    private void reportQuietly(ProgressReporter reporter, int progress, String stage) {
        if (reporter == null) {
            return;
        }
        try {
            reporter.report(Math.min(95, progress), stage);
        } catch (Exception e) {
            log.debug("进度上报失败：{}", e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentToolException("AI 编辑等待被中断");
        }
    }
}

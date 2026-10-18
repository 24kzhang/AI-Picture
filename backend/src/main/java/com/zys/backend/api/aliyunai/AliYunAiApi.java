package com.zys.backend.api.aliyunai;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Component
public class AliYunAiApi {

    // 读取配置文件
    @Value("${aliYunAi.apiKey}")
    private String apiKey;
    @Value("${aliYunAi.imageEditModel:qwen-image-2.0-pro}")
    private String imageEditModel;

    @Resource
    private QwenImageEditor qwenImageEditor;


    /**
     * 创建任务
     *
     * @param createOutPaintingTaskRequest
     * @return
     */
    public CreateOutPaintingTaskResponse createOutPaintingTask(CreateOutPaintingTaskRequest createOutPaintingTaskRequest) {
        return createOutPaintingTask(createOutPaintingTaskRequest, null);
    }

    public CreateOutPaintingTaskResponse createOutPaintingTask(CreateOutPaintingTaskRequest createOutPaintingTaskRequest,
                                                                Long ownerId) {
        if (createOutPaintingTaskRequest == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图参数为空");
        }
        CreateOutPaintingTaskRequest.Input input = createOutPaintingTaskRequest.getInput();
        if (input == null || StrUtil.isBlank(input.getImageUrl())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待编辑图片地址不能为空");
        }
        String prompt = "只补全新增画布区域，保持原图主体、构图和风格自然一致";
        return ownerId == null
                ? qwenImageEditor.submitOutpaint(input.getImageUrl(), prompt,
                createOutPaintingTaskRequest.getParameters())
                : qwenImageEditor.submitOutpaint(input.getImageUrl(), prompt,
                createOutPaintingTaskRequest.getParameters(), ownerId);
    }


    /**
     * 创建自然语言图片编辑任务
     */
    public CreateOutPaintingTaskResponse createImageEditTask(String imageUrl, String prompt, Double strength) {
        return createImageEditTask(imageUrl, prompt, strength, null);
    }

    public CreateOutPaintingTaskResponse createImageEditTask(String imageUrl, String prompt,
                                                               Double strength, byte[] mask) {
        return createImageEditTask(imageUrl, prompt, strength, mask, true);
    }

    public CreateOutPaintingTaskResponse createImageEditTask(String imageUrl, String prompt,
                                                               Double strength, byte[] mask, boolean keepAlpha) {
        return createImageEditTask(imageUrl, prompt, strength, mask, keepAlpha, null);
    }

    public CreateOutPaintingTaskResponse createImageEditTask(String imageUrl, String prompt,
                                                               Double strength, byte[] mask, boolean keepAlpha,
                                                               Long ownerId) {
        if (qwenImageEditor == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "图像编辑服务未初始化");
        }
        if (StrUtil.isBlank(imageUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "待编辑图片地址不能为空");
        }
        if (StrUtil.isBlank(prompt) || prompt.length() > 800) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编辑指令长度需为 1 到 800 个字符");
        }
        double editStrength = strength == null ? 0.5D : strength;
        if (!Double.isFinite(editStrength) || editStrength < 0D || editStrength > 1D) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编辑强度需在 0 到 1 之间");
        }

        // 保留旧适配器签名，已有调用方和单元测试无需感知服务器模型切换；
        // 该重载内部同样只转发到 Qwen-Image 服务器。
        if (keepAlpha && (mask == null || mask.length == 0)) {
            return ownerId == null
                    ? qwenImageEditor.submit(apiKey, imageEditModel, imageUrl, prompt.trim())
                    : qwenImageEditor.submit(imageUrl, prompt.trim(), null, true, ownerId);
        }
        return qwenImageEditor.submit(imageUrl, prompt.trim(), mask, keepAlpha, ownerId);
    }
    /**
     * 查询创建的任务结果
     *
     * @param taskId
     * @return
     */
    public GetOutPaintingTaskResponse getOutPaintingTask(String taskId) {
        return getOutPaintingTask(taskId, null);
    }

    public GetOutPaintingTaskResponse getOutPaintingTask(String taskId, Long ownerId) {
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务 ID 不能为空");
        }
        return ownerId == null ? qwenImageEditor.getTask(taskId) : qwenImageEditor.getTask(taskId, ownerId);
    }
}

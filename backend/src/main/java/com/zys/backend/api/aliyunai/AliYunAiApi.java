package com.zys.backend.api.aliyunai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.api.aliyunai.model.GetOutPaintingTaskResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

@Slf4j
@Component
public class AliYunAiApi {

    // 读取配置文件
    @Value("${aliYunAi.apiKey}")
    private String apiKey;
    @Value("${aliYunAi.imageEditModel:qwen-image-2.0-pro}")
    private String imageEditModel;

    @Resource
    private QwenImageEditor qwenImageEditor;


    // 创建任务地址
    public static final String CREATE_OUT_PAINTING_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/out-painting";
    // 创建通用图片编辑任务地址
    public static final String CREATE_IMAGE_EDIT_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image2image/image-synthesis";


    // 查询任务状态
    public static final String GET_OUT_PAINTING_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/%s";


    /**
     * 创建任务
     *
     * @param createOutPaintingTaskRequest
     * @return
     */
    public CreateOutPaintingTaskResponse createOutPaintingTask(CreateOutPaintingTaskRequest createOutPaintingTaskRequest) {
        if (createOutPaintingTaskRequest == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "扩图参数为空");
        }
        // 发送请求
        HttpRequest httpRequest = HttpRequest.post(CREATE_OUT_PAINTING_TASK_URL)
                .header("Authorization", "Bearer " + apiKey)
                // 必须开启异步处理
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(createOutPaintingTaskRequest));
        // 处理响应
        try (HttpResponse httpResponse = httpRequest.execute()) {
            if (!httpResponse.isOk()) {
                log.error("请求异常：{}", httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 扩图失败");
            }
            CreateOutPaintingTaskResponse createOutPaintingTaskResponse = JSONUtil.toBean(httpResponse.body(), CreateOutPaintingTaskResponse.class);
            if (createOutPaintingTaskResponse.getCode() != null) {
                String errorMessage = createOutPaintingTaskResponse.getMessage();
                log.error("请求异常：{}", errorMessage);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 扩图失败，" + errorMessage);
            }
            return createOutPaintingTaskResponse;
        }
    }


    /**
     * 创建自然语言图片编辑任务
     */
    public CreateOutPaintingTaskResponse createImageEditTask(String imageUrl, String prompt, Double strength) {
        if (StrUtil.isBlank(apiKey)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未配置阿里云百炼 API Key");
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

        if (imageEditModel.startsWith("qwen-image-")) {
            return qwenImageEditor.submit(apiKey, imageEditModel, imageUrl, prompt.trim());
        }

        JSONObject input = JSONUtil.createObj()
                .set("function", "description_edit")
                .set("prompt", prompt.trim())
                .set("base_image_url", imageUrl);
        JSONObject parameters = JSONUtil.createObj()
                .set("n", 1)
                .set("watermark", false)
                .set("strength", editStrength);
        JSONObject requestBody = JSONUtil.createObj()
                .set("model", imageEditModel)
                .set("input", input)
                .set("parameters", parameters);

        HttpRequest httpRequest = HttpRequest.post(CREATE_IMAGE_EDIT_TASK_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .body(requestBody.toString());
        try (HttpResponse httpResponse = httpRequest.execute()) {
            if (!httpResponse.isOk()) {
                log.error("AI 图片编辑请求异常：{}", httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 图片编辑任务创建失败");
            }
            CreateOutPaintingTaskResponse response = JSONUtil.toBean(httpResponse.body(), CreateOutPaintingTaskResponse.class);
            if (StrUtil.isNotBlank(response.getCode())) {
                log.error("AI 图片编辑请求异常：{}", response.getMessage());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 图片编辑任务创建失败，" + response.getMessage());
            }
            return response;
        }
    }
    /**
     * 查询创建的任务结果
     *
     * @param taskId
     * @return
     */
    public GetOutPaintingTaskResponse getOutPaintingTask(String taskId) {
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务 ID 不能为空");
        }
        if (taskId.startsWith(QwenImageEditor.TASK_PREFIX)) {
            return qwenImageEditor.getTask(taskId);
        }
        // 处理响应
        String url = String.format(GET_OUT_PAINTING_TASK_URL, taskId);
        try (HttpResponse httpResponse = HttpRequest.get(url)
                .header("Authorization", "Bearer " + apiKey)
                .execute()) {
            if (!httpResponse.isOk()) {
                log.error("请求异常：{}", httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取任务结果失败");
            }
            return JSONUtil.toBean(httpResponse.body(), GetOutPaintingTaskResponse.class);
        }
    }
}

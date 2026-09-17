package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.PlannerOutcome;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureEditSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划服务：读取画布摘要，调用 DashScope OpenAI 兼容接口的规划模型，
 * 返回文本回复与工具调用计划。不引入 Spring AI，沿用项目 Hutool HTTP 风格。
 */
@Slf4j
@Service
public class AgentPlannerService {

    private static final String SYSTEM_PROMPT = ""
            + "你是云图库的修图规划助手。根据用户指令与画布摘要，选择最少的工具完成任务。\n"
            + "规则：\n"
            + "1. 一次最多规划 8 步；能用 1 步完成就不要多步。\n"
            + "2. 只在需要修图时调用工具；闲聊或咨询直接文本回复，不调用工具。\n"
            + "3. 工具按执行顺序排列；有先后依赖时用 depends_on 引用前序步骤 id。\n"
            + "4. 选区与遮罩参数由系统自动注入，你不要输出 maskAssetId、revision。\n"
            + "5. 换背景用 replace_background；局部改内容用 replace_region；消除物体用 erase_region；"
            + "整体明暗色彩用 adjust_image；提高分辨率用 upscale_image；扩画幅用 expand_canvas；"
            + "出投放尺寸用 prepare_delivery_sizes；电商营销图用 generate_marketing；多图批量用 batch_process。\n"
            + "6. 图层类操作（缩放、移动、旋转、透明度、显隐、文字、顺序、裁剪）只改画布文档，不调用生成模型。";

    @Value("${agent.planner.baseUrl:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${agent.planner.apiKey:${aliYunAi.apiKey:}}")
    private String apiKey;

    @Value("${agent.planner.model:qwen-plus}")
    private String model;

    @Value("${agent.planner.timeoutMs:120000}")
    private int timeoutMs;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private LayerDocumentService layerDocumentService;

    @Resource
    private SelectionService selectionService;

    /**
     * 规划模型是否可用
     */
    public boolean available() {
        return StrUtil.isNotBlank(apiKey);
    }

    /**
     * 针对一轮自然语言指令生成规划结果
     */
    public PlannerOutcome plan(PictureEditSession session, Picture picture, String goal) {
        if (!available()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "未配置规划模型 API Key，对话指令不可用（agent.planner.apiKey）");
        }
        JSONObject summary = buildCanvasSummary(session, picture);
        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", SYSTEM_PROMPT));
        messages.add(new JSONObject().set("role", "user").set("content",
                "画布摘要：\n" + summary.toStringPretty() + "\n\n用户指令：" + goal));

        JSONObject body = new JSONObject()
                .set("model", model)
                .set("messages", messages)
                .set("temperature", 0)
                .set("tools", toolRegistry.toAgentToolSchemas())
                .set("tool_choice", "auto");

        HttpRequest request = HttpRequest.post(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeoutMs)
                .body(body.toString());

        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                log.error("规划模型请求失败：{} {}", response.getStatus(), response.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "规划模型请求失败，请稍后重试");
            }
            return parseResponse(response.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("规划模型调用异常", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "规划模型调用异常：" + e.getMessage());
        }
    }

    /**
     * 画布摘要：画幅、图层、当前图、revision、是否有选区
     */
    public JSONObject buildCanvasSummary(PictureEditSession session, Picture picture) {
        JSONObject summary = new JSONObject(true);
        JSONObject document = layerDocumentService.parseDocument(session);
        if (document != null) {
            summary.set("width", document.getInt("width"));
            summary.set("height", document.getInt("height"));
            JSONArray layers = new JSONArray();
            for (Object raw : layerDocumentService.layers(document)) {
                JSONObject layer = (JSONObject) raw;
                JSONObject item = new JSONObject(true)
                        .set("id", layer.getStr("id"))
                        .set("name", layer.getStr("name"))
                        .set("kind", layer.getStr("kind"))
                        .set("visible", layer.getBool("visible", true));
                if (LayerDocumentService.KIND_TEXT.equals(layer.getStr("kind"))) {
                    item.set("text", layer.getStr("text"));
                }
                layers.add(item);
            }
            summary.set("layers", layers);
        }
        summary.set("revision", session.getRevision());
        summary.set("hasSelection", selectionService.getValidSelection(session) != null);
        if (picture != null) {
            summary.set("pictureName", picture.getName());
            summary.set("pictureFormat", picture.getPicFormat());
        }
        return summary;
    }

    private PlannerOutcome parseResponse(String responseBody) {
        PlannerOutcome outcome = new PlannerOutcome();
        JSONObject json = JSONUtil.parseObj(responseBody);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "规划模型未返回结果");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "规划模型返回格式异常");
        }
        outcome.setReply(message.getStr("content"));
        JSONArray toolCalls = message.getJSONArray("tool_calls");
        if (toolCalls == null) {
            return outcome;
        }
        List<Map<String, Object>> rawSteps = new ArrayList<>();
        for (Object raw : toolCalls) {
            JSONObject call = (JSONObject) raw;
            JSONObject function = call.getJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.getStr("name");
            Map<String, Object> arguments = new LinkedHashMap<>();
            String argText = function.getStr("arguments");
            if (StrUtil.isNotBlank(argText)) {
                try {
                    arguments = JSONUtil.toBean(argText, LinkedHashMap.class);
                } catch (Exception e) {
                    log.warn("规划模型工具参数解析失败：{}", argText);
                }
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("tool", name);
            step.put("params", arguments);
            rawSteps.add(step);
        }
        outcome.setRawSteps(rawSteps);
        return outcome;
    }
}

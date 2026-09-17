package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.provider.EditImageRequest;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 像素类异步工具：remove_background（Provider 抠图）、adjust_image（本地 Java2D 调色）。
 * 结果写回目标图层，画布 revision 递增由执行外壳统一处理。
 */
@Component
public class PixelTools extends SessionToolBase implements AgentTool {

    @Override
    public List<ToolSpec> specs() {
        return Arrays.asList(removeBackground(), adjustImage());
    }

    private ToolSpec removeBackground() {
        List<ParamField> params = Collections.singletonList(
                ParamField.string("layerId")
                        .setDescription("要处理的图层 id 或名字。不填则作用在最上层可见图像。"));
        return new ToolSpec()
                .setName("remove_background")
                .setLabel("去背景")
                .setDescription("识别指定图层的主体并去掉背景。默认最上层图像。不要用它来换背景或生成新画面。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setHandler(this::handleRemoveBackground);
    }

    private Map<String, Object> handleRemoveBackground(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        JSONObject document = document(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        ctx.report(20, "识别主体");
        byte[] source = layerBytes(session, layer);
        ctx.report(40, "去除背景");
        EditImageRequest request = new EditImageRequest()
                .setPrompt("去掉背景，只保留画面主体，背景区域填充纯白色，主体边缘保持干净完整")
                .setImage(source)
                .setCount(1);
        List<byte[]> outputs = imageProvider.edit(request, ctx.getReporter());
        if (outputs == null || outputs.isEmpty()) {
            throw new com.zys.backend.agent.tool.AgentToolException("去背景没有返回结果");
        }
        ctx.report(85, "写回图层");
        PictureAgentAsset asset = save(session, AgentAssetKindEnum.CANDIDATE, "remove_background", outputs.get(0));
        rewriteLayer(layer, asset);
        Map<String, Object> result = ToolResults.document(document);
        result.put("assetIds", ToolResults.assetIds(Collections.singletonList(asset)));
        return result;
    }

    private ToolSpec adjustImage() {
        List<ParamField> params = new ArrayList<>();
        params.add(ParamField.string("layerId")
                .setDescription("要处理的图层 id 或名字。不填则作用在最上层可见图像。"));
        String[] names = {"brightness", "contrast", "highlights", "shadows", "temperature",
                "tint", "saturation", "vibrance", "sharpness", "clarity"};
        for (String name : names) {
            params.add(ParamField.number(name).setMin(-1.0).setMax(1.0).setDefaultValue(0.0)
                    .setDescription(descriptionOf(name)));
        }
        params.add(ParamField.number("vignette").setMin(0.0).setMax(1.0).setDefaultValue(0.0)
                .setDescription("晕影强度，0 到 1"));
        return new ToolSpec()
                .setName("adjust_image")
                .setLabel("调色")
                .setDescription("调整指定图层的亮度、对比度、高光、阴影、色温、色调、饱和度、自然饱和度、锐化、清晰度和晕影。"
                        + "默认最上层图像。参数取值 -1 到 1，晕影为 0 到 1。未提到的参数保持 0。"
                        + "改成某个具体颜色或换材质要用 replace_region，不要用它。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setHandler(this::handleAdjust);
    }

    private Map<String, Object> handleAdjust(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        JSONObject document = document(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        ctx.report(20, "读取图层");
        byte[] source = layerBytes(session, layer);
        ctx.report(60, "调整色彩");
        byte[] output = AgentImageOps.adjust(source, params);
        ctx.report(85, "写回图层");
        PictureAgentAsset asset = save(session, AgentAssetKindEnum.CANDIDATE, "adjust_image", output);
        rewriteLayer(layer, asset);
        Map<String, Object> result = ToolResults.document(document);
        result.put("assetIds", ToolResults.assetIds(Collections.singletonList(asset)));
        return result;
    }

    private String descriptionOf(String name) {
        switch (name) {
            case "brightness":
                return "亮度，-1 到 1";
            case "contrast":
                return "对比度，-1 到 1";
            case "highlights":
                return "高光，-1 到 1";
            case "shadows":
                return "阴影，-1 到 1";
            case "temperature":
                return "色温，-1 冷 到 1 暖";
            case "tint":
                return "色调，-1 绿 到 1 品红";
            case "saturation":
                return "饱和度，-1 到 1";
            case "vibrance":
                return "自然饱和度，-1 到 1";
            case "sharpness":
                return "锐化，-1 到 1";
            default:
                return "清晰度，-1 到 1";
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.provider.EditImageRequest;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 整图生成类异步工具：replace_background / expand_canvas / upscale_image。
 * 单张结果直接写回画布（adopt）；多张候选只上资产墙，由用户点选采用。
 */
@Component
public class BackgroundTools extends SessionToolBase implements AgentTool {

    @Override
    public List<ToolSpec> specs() {
        return Arrays.asList(replaceBackground(), expandCanvas(), upscaleImage());
    }

    private ToolSpec replaceBackground() {
        List<ParamField> params = Arrays.asList(
                ParamField.string("prompt").setRequired(true).setMinLength(1).setMaxLength(500)
                        .setDescription("新背景的文字描述"),
                ParamField.integer("count").setMin(1.0).setMax(4.0).setDefaultValue(1)
                        .setDescription("候选数量 1-4，多于一张时不自动上画布"),
                ParamField.string("negativePrompt").setAgentHidden(true)
                        .setDescription("反向提示词（服务端注入）"));
        return new ToolSpec()
                .setName("replace_background")
                .setLabel("换背景")
                .setDescription("按文字描述替换背景。一次可出 1 到 4 张候选；多于一张时不自动上画布，用户点选图片墙采用。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setHandler(this::handleReplaceBackground);
    }

    private Map<String, Object> handleReplaceBackground(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        ctx.report(15, "读取画布");
        byte[] source = canvasBytes(session);
        ctx.report(30, "生成新背景");
        int count = intValue(params.get("count"), 1);
        EditImageRequest request = new EditImageRequest()
                .setPrompt("只替换背景，保持主体、光线和边缘不变。新背景：" + params.get("prompt"))
                .setImage(source)
                .setCount(count);
        Object negative = params.get("negativePrompt");
        if (negative != null) {
            request.setNegativePrompt(String.valueOf(negative));
        }
        List<byte[]> outputs = imageProvider.edit(request, ctx.getReporter());
        return storeOutputs(ctx, session, outputs, count == 1, AgentAssetKindEnum.CANDIDATE, "replace_background");
    }

    private ToolSpec expandCanvas() {
        List<ParamField> params = Arrays.asList(
                ParamField.string("ratio").setRequired(true)
                        .setChoices(new ArrayList<Object>(RatioConstants.ALL))
                        .setDescription("目标比例"),
                ParamField.string("prompt").setMaxLength(500)
                        .setDefaultValue("自然延伸画面边缘，保持主体完整")
                        .setDescription("补全区域的描述"));
        return new ToolSpec()
                .setName("expand_canvas")
                .setLabel("扩图")
                .setDescription("把当前画布扩展到指定比例，并自然补全新增区域。主体保持完整，不要裁切。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setHandler(this::handleExpandCanvas);
    }

    private Map<String, Object> handleExpandCanvas(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        JSONObject document = document(ctx);
        int width = document.getInt("width", 0);
        int height = document.getInt("height", 0);
        if (width <= 0 || height <= 0) {
            throw new AgentToolException("画布尺寸缺失，无法扩图");
        }
        String ratio = String.valueOf(params.get("ratio"));
        int[] target = RatioConstants.coverSize(width, height, ratio);
        ctx.report(15, "读取画布");
        byte[] source = canvasBytes(session);
        ctx.report(30, "延伸画幅");
        EditImageRequest request = new EditImageRequest()
                .setPrompt(String.valueOf(params.get("prompt")))
                .setImage(source)
                .setWidth(target[0])
                .setHeight(target[1])
                .setCount(1);
        List<byte[]> outputs = imageProvider.edit(request, ctx.getReporter());
        return storeOutputs(ctx, session, outputs, true, AgentAssetKindEnum.CANDIDATE, "expand_canvas");
    }

    private ToolSpec upscaleImage() {
        List<ParamField> params = java.util.Collections.singletonList(
                ParamField.integer("scale").setMin(2.0).setMax(4.0).setDefaultValue(2)
                        .setDescription("放大倍数，2 或 4，默认 2"));
        return new ToolSpec()
                .setName("upscale_image")
                .setLabel("超分")
                .setDescription("提高当前画布分辨率。scale 为 2 或 4，默认 2 倍。不要用它换内容或改构图。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setHandler(this::handleUpscale);
    }

    private Map<String, Object> handleUpscale(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        ctx.report(15, "读取画布");
        byte[] source = canvasBytes(session);
        ctx.report(30, "提升分辨率");
        byte[] output = imageProvider.upscale(source, intValue(params.get("scale"), 2), ctx.getReporter());
        return storeOutputs(ctx, session, java.util.Collections.singletonList(output), true,
                AgentAssetKindEnum.CANDIDATE, "upscale_image");
    }

    /**
     * 存储产出：adopt=true 时写回画布 base 层；否则只上资产墙
     */
    private Map<String, Object> storeOutputs(ToolRunContext ctx,
                                             PictureEditSession session,
                                             List<byte[]> outputs,
                                             boolean adopt,
                                             AgentAssetKindEnum kind,
                                             String source) {
        if (outputs == null || outputs.isEmpty()) {
            throw new AgentToolException("生成没有返回结果");
        }
        List<PictureAgentAsset> assets = new ArrayList<>();
        for (byte[] data : outputs) {
            ctx.report(90, "保存结果");
            assets.add(save(session, kind, source, data));
        }
        if (!adopt || assets.size() > 1) {
            ctx.report(95, "候选已加入图片墙，点选采用");
            return ToolResults.wall(assets);
        }
        JSONObject document = documentService.adoptIntoDocument(document(ctx), assets.get(0));
        return ToolResults.documentWithAdopt(document, assets.get(0));
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

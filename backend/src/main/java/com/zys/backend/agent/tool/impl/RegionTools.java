package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.provider.EditImageRequest;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.awt.image.BufferedImage;

/**
 * 局部编辑异步工具：erase_region / replace_region。
 * 选区限定“改哪里”，图层限定“改谁”，两者同时存在取交集；
 * maskAssetId 与 revision 由服务端注入，对模型隐藏。
 */
@Component
public class RegionTools extends SessionToolBase implements AgentTool {

    @Override
    public List<ToolSpec> specs() {
        return Arrays.asList(eraseRegion(), replaceRegion());
    }

    private List<ParamField> commonParams(boolean promptRequired) {
        ParamField prompt = ParamField.string("prompt").setMaxLength(500)
                .setRequired(promptRequired)
                .setDescription(promptRequired ? "替换内容的文字描述" : "消除目标的文字描述（可省略）");
        return Arrays.asList(
                ParamField.string("layerId")
                        .setDescription("点名要修改的图层 id 或名字；不填则作用于选区下最上层图像"),
                prompt,
                ParamField.string("maskAssetId").setAgentHidden(true)
                        .setDescription("选区蒙版资产 id（服务端注入）"),
                ParamField.integer("revision").setAgentHidden(true)
                        .setDescription("选区绑定的画布 revision（服务端注入）"));
    }

    private ToolSpec eraseRegion() {
        return new ToolSpec()
                .setName("erase_region")
                .setLabel("局部消除")
                .setDescription("消除物体，并用周围内容自然填补。"
                        + "有选区时只消除选区内；用 layerId 点名图层时消除该层内容；两者都有取交集。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(commonParams(false))
                .setHandler((ctx, params) -> editRegion(ctx, params,
                        "移除选中物体，用周围背景自然填补，不要改变选区以外的画面。",
                        "移除画面中的物体，用周围背景自然填补。"));
    }

    private ToolSpec replaceRegion() {
        return new ToolSpec()
                .setName("replace_region")
                .setLabel("局部替换")
                .setDescription("按文字描述替换画面内容，改颜色、换材质、换成另一个物体都用它。"
                        + "有选区时只改选区内；用 layerId 点名图层时改该层整层；两者都有取交集。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(commonParams(true))
                .setHandler((ctx, params) -> {
                    String what = String.valueOf(params.get("prompt"));
                    return editRegion(ctx, params,
                            "只改选中区域：" + what + "。选区外的主体、光线和背景必须保持原样。",
                            what + "。保持构图、透视与画幅不变。");
                });
    }

    /**
     * 局部编辑主流程：当前画布 + 选区 mask → Provider 编辑 → 按 mask 合成 → 写回画布
     */
    private Map<String, Object> editRegion(ToolRunContext ctx,
                                           Map<String, Object> params,
                                           String scopedPrompt,
                                           String wholePrompt) {
        PictureEditSession session = session(ctx);
        selectionService.checkRevision(session, params);

        Object rawMaskAssetId = params.get("maskAssetId");
        PictureAgentAsset mask = rawMaskAssetId == null ? null
                : assetService.getInSession(session.getId(), Long.valueOf(String.valueOf(rawMaskAssetId)));

        ctx.report(20, "读取画布");
        JSONObject document = document(ctx);
        String targetId = params.get("layerId") == null ? null : String.valueOf(params.get("layerId")).trim();
        JSONObject target = targetId == null || targetId.isEmpty() ? null : documentService.resolveTarget(document, targetId);
        if (target != null && !LayerDocumentService.KIND_IMAGE.equals(target.getStr("kind"))) {
            throw new AgentToolException("局部生成只能修改图像图层，文字请使用改文字工具");
        }
        byte[] source = target == null ? canvasBytes(session) : layerBytes(session, target);
        BufferedImage sourceImage = AgentImageOps.read(source);
        byte[] effectiveMask = mask == null ? null : assetService.loadBytes(mask);
        if (target != null && effectiveMask != null) {
            effectiveMask = LayerMaskProjection.project(effectiveMask, document, target,
                    sourceImage.getWidth(), sourceImage.getHeight());
        }

        String prompt = mask == null ? wholePrompt : scopedPrompt;
        ctx.report(40, "局部生成");
        EditImageRequest request = new EditImageRequest()
                .setPrompt(prompt)
                .setImage(source)
                .setCount(1);
        if (effectiveMask != null) {
            request.setMask(effectiveMask);
        }
        List<byte[]> outputs = imageProvider.edit(request, ctx.getReporter());
        if (outputs == null || outputs.isEmpty()) {
            throw new AgentToolException("局部编辑没有返回结果");
        }

        ctx.report(85, "合并结果");
        byte[] merged;
        if (effectiveMask == null) {
            merged = target == null ? outputs.get(0)
                    : AgentImageOps.resizeExact(outputs.get(0), sourceImage.getWidth(), sourceImage.getHeight());
        } else {
            merged = AgentImageOps.applyMasked(source, outputs.get(0), effectiveMask);
        }

        selectionService.clear(session.getId());
        PictureAgentAsset asset = save(session, AgentAssetKindEnum.CANDIDATE,
                ctx.getToolRun().getTool(), merged);
        Map<String, Object> result;
        if (target == null) {
            result = ToolResults.documentWithAdopt(documentService.adoptIntoDocument(document, asset), asset);
        } else {
            rewriteLayer(target, asset);
            result = ToolResults.document(document);
            result.put("assetIds", ToolResults.assetIds(Collections.singletonList(asset)));
            result.put("layerId", target.getStr("id"));
        }
        result.put("maskUsed", mask != null);
        return result;
    }

    /**
     * 消除工具默认描述（保留给规划器 prompt 使用）
     */
    static List<String> defaultErasePrompts() {
        return Collections.singletonList("移除选中物体，用周围背景自然填补");
    }
}

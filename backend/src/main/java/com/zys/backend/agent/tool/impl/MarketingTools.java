package com.zys.backend.agent.tool.impl;

import cn.hutool.core.util.StrUtil;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交付类工具：generate_marketing（异步营销图）、prepare_delivery_sizes（同步多尺寸）。
 */
@Component
public class MarketingTools extends SessionToolBase implements AgentTool {

    private static final Map<String, String> MARKETING_PROMPTS = new LinkedHashMap<>();

    static {
        MARKETING_PROMPTS.put("product",
                "把商品放在纯白背景上，居中构图，柔和顶光，边缘干净，保留商品原有材质与颜色，输出电商主图。");
        MARKETING_PROMPTS.put("scene",
                "把商品放到真实生活场景中，侧逆光、浅景深，氛围自然，保留商品原有材质与颜色。");
        MARKETING_PROMPTS.put("model",
                "生成模特手持或佩戴该商品的半身展示图，简洁室内背景、自然光，商品细节清晰可辨。");
        MARKETING_PROMPTS.put("poster",
                "做一张促销海报，突出商品，右侧或下方留出文字区，构图干净有冲击力。");
    }

    @Override
    public List<ToolSpec> specs() {
        return Arrays.asList(generateMarketing(), prepareDeliverySizes());
    }

    private ToolSpec generateMarketing() {
        List<ParamField> params = Arrays.asList(
                ParamField.string("kind").setRequired(true)
                        .setChoices(new ArrayList<Object>(MARKETING_PROMPTS.keySet()))
                        .setDescription("product 商品主图、scene 场景氛围图、model 模特上身、poster 促销海报"),
                ParamField.string("caption").setMaxLength(200)
                        .setDescription("海报文案或补充要求"),
                ParamField.integer("count").setMin(1.0).setMax(4.0).setDefaultValue(1)
                        .setDescription("候选数量 1-4"),
                ParamField.string("ratio").setDefaultValue(RatioConstants.SQUARE)
                        .setChoices(new ArrayList<Object>(RatioConstants.ALL))
                        .setDescription("输出比例，默认 1:1"));
        return new ToolSpec()
                .setName("generate_marketing")
                .setLabel("营销图")
                .setDescription("按当前画布生成电商营销图，只进图片墙，不改当前画布。"
                        + "kind：product 商品主图（白底）、scene 场景氛围图、model 模特上身、poster 促销海报。"
                        + "caption 可填海报标题或补充要求。")
                .setQueued(true)
                .setSessionRequired(true)
                .setParams(params)
                .setDescription("按当前画布生成电商营销图，只进图片墙，不改当前画布。"
                        + "kind：product 商品主图（白底）、scene 场景氛围图、model 模特上身、poster 促销海报。"
                        + "caption 可填海报标题或补充要求。")
                .setHandler(this::handleGenerateMarketing);
    }

    private Map<String, Object> handleGenerateMarketing(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        String kind = String.valueOf(params.get("kind"));
        String promptBase = MARKETING_PROMPTS.get(kind);
        if (promptBase == null) {
            throw new AgentToolException("不支持的营销图类型：" + kind);
        }
        String caption = params.get("caption") == null ? "" : String.valueOf(params.get("caption")).trim();
        String prompt = caption.isEmpty() ? promptBase : promptBase + " 文案：" + caption;
        int[] size = RatioConstants.sizeOf(String.valueOf(
                params.getOrDefault("ratio", RatioConstants.SQUARE)));
        int count = params.get("count") == null ? 1 : (int) Math.round(Double.parseDouble(String.valueOf(params.get("count"))));

        ctx.report(15, "读取画布");
        byte[] source = canvasBytes(session);
        ctx.report(30, "生成营销图");
        EditImageRequest request = new EditImageRequest()
                .setPrompt(prompt)
                .setImage(source)
                .setCount(count)
                .setWidth(size[0])
                .setHeight(size[1]);
        List<byte[]> outputs = imageProvider.edit(request, ctx.getReporter());
        if (outputs == null || outputs.isEmpty()) {
            throw new AgentToolException("营销图生成没有返回结果");
        }
        List<PictureAgentAsset> assets = new ArrayList<>();
        List<Map<String, Object>> variants = new ArrayList<>();
        for (byte[] data : outputs) {
            byte[] fitted = AgentImageOps.letterbox(data, size[0], size[1]);
            assets.add(save(session, AgentAssetKindEnum.MARKETING, "generate_marketing", fitted));
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("kind", kind);
            variant.put("ratio", params.getOrDefault("ratio", RatioConstants.SQUARE));
            variant.put("width", size[0]);
            variant.put("height", size[1]);
            variants.add(variant);
        }
        ctx.report(95, "营销图已加入图片墙");
        return ToolResults.wallWithVariants(assets, variants);
    }

    private ToolSpec prepareDeliverySizes() {
        ParamField ratios = ParamField.array("ratios", ParamField.TYPE_STRING)
                .setItemChoices(new ArrayList<Object>(RatioConstants.ALL))
                .setMinItems(1)
                .setMaxItems(RatioConstants.ALL.size())
                .setDefaultValue(new ArrayList<Object>(RatioConstants.DELIVERY))
                .setDescription("要输出的投放比例，默认 1:1、4:5、9:16。主体不被裁切。");
        return new ToolSpec()
                .setName("prepare_delivery_sizes")
                .setLabel("投放尺寸")
                .setDescription("把当前画布完整放入投放比例，不裁切、不改画面，只进图片墙。"
                        + "默认一次出 1:1、4:5、9:16。用户说改尺寸、出投放物料时用这个，不要用 crop_canvas。")
                .setQueued(false)
                .setSessionRequired(true)
                .setParams(java.util.Collections.singletonList(ratios))
                .setHandler(this::handleDeliverySizes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleDeliverySizes(ToolRunContext ctx, Map<String, Object> params) {
        PictureEditSession session = session(ctx);
        Set<String> ratios = new LinkedHashSet<>();
        Object raw = params.get("ratios");
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    ratios.add(String.valueOf(item));
                }
            }
        }
        if (ratios.isEmpty()) {
            ratios.addAll(RatioConstants.DELIVERY);
        }
        ctx.report(20, "读取画布");
        byte[] source = canvasBytes(session);
        List<PictureAgentAsset> assets = new ArrayList<>();
        List<Map<String, Object>> variants = new ArrayList<>();
        int total = ratios.size();
        int index = 0;
        for (String ratio : ratios) {
            int[] size = RatioConstants.sizeOf(ratio);
            ctx.report(30 + 60 * index / total, "适配 " + ratio);
            byte[] fitted = AgentImageOps.letterbox(source, size[0], size[1]);
            assets.add(save(session, AgentAssetKindEnum.DELIVERY, "prepare_delivery_sizes", fitted));
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("kind", "export");
            variant.put("ratio", ratio);
            variant.put("width", size[0]);
            variant.put("height", size[1]);
            variants.add(variant);
            index++;
        }
        ctx.report(95, "投放尺寸已加入图片墙");
        return ToolResults.wallWithVariants(assets, variants);
    }
}

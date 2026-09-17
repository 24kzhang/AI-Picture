package com.zys.backend.agent.tool.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.model.entity.PictureEditSession;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 文档类同步工具：只改 LayerDocument，不调用生成模型，当前请求内执行。
 * crop_canvas / flip_layer / set_layer_opacity / set_layer_visible / set_layer_text /
 * reorder_layer / scale_layer / rotate_layer / move_layer
 */
@Component
public class DocumentTools implements AgentTool {

    private static final String HEX_COLOR = "^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$";

    @Resource
    private LayerDocumentService documentService;

    @Override
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>();
        specs.add(cropCanvas());
        specs.add(flipLayer());
        specs.add(setLayerOpacity());
        specs.add(setLayerVisible());
        specs.add(setLayerText());
        specs.add(reorderLayer());
        specs.add(scaleLayer());
        specs.add(rotateLayer());
        specs.add(moveLayer());
        return specs;
    }

    private ParamField layerIdField() {
        return ParamField.string("layerId")
                .setDescription("要修改的图层 id 或名字，如 base、图层2。不填则作用在最上层可见图层。");
    }

    private ToolSpec doc(String name, String label, String description, List<ParamField> params) {
        return new ToolSpec()
                .setName(name)
                .setLabel(label)
                .setDescription(description)
                .setQueued(false)
                .setSessionRequired(true)
                .setParams(params);
    }

    private ToolSpec cropCanvas() {
        List<ParamField> params = Arrays.asList(
                ParamField.string("ratio")
                        .setChoices(new ArrayList<Object>(RatioConstants.ALL))
                        .setDescription("目标比例，按比例居中裁剪"),
                ParamField.object("rect")
                        .setDescription("归一化裁剪框 {x,y,width,height}，取值 0-1"));
        return doc("crop_canvas", "裁剪",
                "按比例或归一化矩形裁切画布。只改图层文档，不调用生成模型。", params)
                .setCrossValidator(normalized -> {
                    boolean hasRatio = normalized.get("ratio") != null;
                    boolean hasRect = normalized.get("rect") != null;
                    if (hasRatio == hasRect) {
                        return "裁剪需要 ratio 或 rect 之一";
                    }
                    if (hasRect) {
                        Map<?, ?> rect = (Map<?, ?>) normalized.get("rect");
                        double x = dv(rect.get("x"));
                        double y = dv(rect.get("y"));
                        double width = dv(rect.get("width"));
                        double height = dv(rect.get("height"));
                        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                            return "裁剪框数值非法";
                        }
                        if (x + width > 1.0001 || y + height > 1.0001) {
                            return "裁剪框超出画布";
                        }
                    }
                    return null;
                })
                .setHandler(this::handleCrop);
    }

    private Map<String, Object> handleCrop(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        int width = document.getInt("width", 0);
        int height = document.getInt("height", 0);
        double rx;
        double ry;
        double rw;
        double rh;
        if (params.get("rect") != null) {
            Map<?, ?> rect = (Map<?, ?>) params.get("rect");
            rx = dv(rect.get("x"));
            ry = dv(rect.get("y"));
            rw = dv(rect.get("width"));
            rh = dv(rect.get("height"));
        } else {
            int[] parts = ratioParts(String.valueOf(params.get("ratio")));
            double target = (double) parts[0] / parts[1];
            double current = height == 0 ? target : (double) width / height;
            if (current > target) {
                rh = 1.0;
                rw = target / current;
            } else {
                rw = 1.0;
                rh = current / target;
            }
            rx = (1 - rw) / 2;
            ry = (1 - rh) / 2;
        }
        int cropX = (int) Math.round(rx * width);
        int cropY = (int) Math.round(ry * height);
        int cropWidth = Math.max(1, (int) Math.round(rw * width));
        int cropHeight = Math.max(1, (int) Math.round(rh * height));
        for (Object raw : documentService.layers(document)) {
            JSONObject layer = (JSONObject) raw;
            layer.set("x", dv(layer.get("x")) - cropX);
            layer.set("y", dv(layer.get("y")) - cropY);
        }
        document.set("width", cropWidth);
        document.set("height", cropHeight);
        return ToolResults.document(document);
    }

    private ToolSpec flipLayer() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.string("direction")
                        .setRequired(true)
                        .setChoices(Arrays.<Object>asList("horizontal", "vertical"))
                        .setDescription("翻转方向"));
        return doc("flip_layer", "翻转", "水平或垂直翻转指定图层，默认最上层图像。", params)
                .setDetail(p -> {
                    String direction = String.valueOf(p.get("direction"));
                    return "horizontal".equals(direction) ? "水平翻转"
                            : "vertical".equals(direction) ? "垂直翻转" : null;
                })
                .setHandler(this::handleFlip);
    }

    private Map<String, Object> handleFlip(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        String direction = String.valueOf(params.get("direction"));
        if ("horizontal".equals(direction)) {
            layer.set("scaleX", -dv(layer.get("scaleX"), 1.0));
        } else {
            layer.set("scaleY", -dv(layer.get("scaleY"), 1.0));
        }
        return ToolResults.document(document);
    }

    private ToolSpec setLayerOpacity() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.number("opacity").setRequired(true).setMin(0.0).setMax(1.0)
                        .setDescription("目标透明度，0 到 1"));
        return doc("set_layer_opacity", "透明度", "设置图层透明度，取值 0 到 1。", params)
                .setHandler(this::handleOpacity);
    }

    private Map<String, Object> handleOpacity(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        layer.set("opacity", dv(params.get("opacity")));
        return ToolResults.document(document);
    }

    private ToolSpec setLayerVisible() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.bool("visible").setRequired(true).setDescription("是否可见"));
        return doc("set_layer_visible", "显隐", "显示或隐藏指定图层，不删除内容。", params)
                .setHandler(this::handleVisible);
    }

    private Map<String, Object> handleVisible(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        layer.set("visible", Boolean.TRUE.equals(params.get("visible")));
        return ToolResults.document(document);
    }

    private ToolSpec setLayerText() {
        List<ParamField> params = Arrays.asList(
                layerIdField().setDescription("文字图层 id 或名字；不填则改最上层可见文字。"),
                ParamField.string("text").setRequired(true).setMaxLength(500).setDescription("新文案"),
                ParamField.number("fontSize").setMin(8.0).setMax(400.0).setDescription("字号"),
                ParamField.string("fill").setPattern(HEX_COLOR).setDescription("颜色，#RGB 或 #RRGGBB"));
        return doc("set_layer_text", "改文字", "修改文字图层的文案，可选字号与颜色。未指定图层时改最上层可见文字。", params)
                .setHandler(this::handleText);
    }

    private Map<String, Object> handleText(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTextTarget(document, str(params.get("layerId")));
        layer.set("text", String.valueOf(params.get("text")));
        if (params.get("fontSize") != null) {
            layer.set("fontSize", dv(params.get("fontSize")));
        }
        if (params.get("fill") != null) {
            layer.set("fill", String.valueOf(params.get("fill")));
        }
        return ToolResults.document(document);
    }

    private ToolSpec reorderLayer() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.string("place").setRequired(true)
                        .setChoices(Arrays.<Object>asList("top", "bottom", "up", "down"))
                        .setDescription("目标位置"));
        return doc("reorder_layer", "图层顺序", "调整图层前后顺序：top / bottom / up / down。", params)
                .setHandler(this::handleReorder);
    }

    private Map<String, Object> handleReorder(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        JSONArray layers = document.getJSONArray("layers");
        int index = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.getJSONObject(i) == layer) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new AgentToolException("图层不在画布中");
        }
        layers.remove(index);
        String place = String.valueOf(params.get("place"));
        switch (place) {
            case "top":
                layers.add(layer);
                break;
            case "bottom":
                layers.add(0, layer);
                break;
            case "up":
                layers.add(Math.min(layers.size(), index + 1), layer);
                break;
            default:
                layers.add(Math.max(0, index - 1), layer);
                break;
        }
        return ToolResults.document(document);
    }

    private ToolSpec scaleLayer() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.number("factor").setMin(0.01).setMax(8.0).setDescription("相对缩放倍率"),
                ParamField.number("scaleX").setMin(0.01).setMax(8.0).setDescription("绝对横向缩放"),
                ParamField.number("scaleY").setMin(0.01).setMax(8.0).setDescription("绝对纵向缩放"),
                ParamField.number("x").setAgentHidden(true).setDescription("界面拖拽回填的横坐标"),
                ParamField.number("y").setAgentHidden(true).setDescription("界面拖拽回填的纵坐标"));
        return doc("scale_layer", "缩放", "缩放指定图层，默认最上层图像。factor 为相对倍率，scaleX / scaleY 为绝对值。", params)
                .setCrossValidator(normalized ->
                        normalized.get("factor") == null && normalized.get("scaleX") == null
                                && normalized.get("scaleY") == null ? "需要相对倍率或绝对缩放" : null)
                .setHandler(this::handleScale);
    }

    private Map<String, Object> handleScale(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        if (params.get("factor") != null) {
            double factor = dv(params.get("factor"));
            layer.set("scaleX", dv(layer.get("scaleX"), 1.0) * factor);
            layer.set("scaleY", dv(layer.get("scaleY"), 1.0) * factor);
        }
        if (params.get("scaleX") != null) {
            layer.set("scaleX", dv(params.get("scaleX")));
        }
        if (params.get("scaleY") != null) {
            layer.set("scaleY", dv(params.get("scaleY")));
        }
        if (params.get("x") != null) {
            layer.set("x", dv(params.get("x")));
        }
        if (params.get("y") != null) {
            layer.set("y", dv(params.get("y")));
        }
        return ToolResults.document(document);
    }

    private ToolSpec rotateLayer() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.number("angle").setMin(-360.0).setMax(360.0).setDescription("相对旋转角度，顺时针为正"),
                ParamField.number("rotation").setMin(-360.0).setMax(360.0).setDescription("绝对旋转角度"));
        return doc("rotate_layer", "旋转", "旋转图层。angle 为相对角度，rotation 为绝对角度，顺时针为正。", params)
                .setCrossValidator(normalized ->
                        normalized.get("angle") == null && normalized.get("rotation") == null
                                ? "需要旋转角度" : null)
                .setHandler(this::handleRotate);
    }

    private Map<String, Object> handleRotate(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        if (params.get("angle") != null) {
            layer.set("rotation", dv(layer.get("rotation")) + dv(params.get("angle")));
        }
        if (params.get("rotation") != null) {
            layer.set("rotation", dv(params.get("rotation")));
        }
        return ToolResults.document(document);
    }

    private ToolSpec moveLayer() {
        List<ParamField> params = Arrays.asList(
                layerIdField(),
                ParamField.number("x").setDescription("绝对横坐标"),
                ParamField.number("y").setDescription("绝对纵坐标"),
                ParamField.number("dx").setDescription("相对横向位移（像素）"),
                ParamField.number("dy").setDescription("相对纵向位移（像素）"));
        return doc("move_layer", "移动", "移动指定图层。x/y 为绝对坐标，dx/dy 为相对像素位移。默认最上层图像。", params)
                .setCrossValidator(normalized ->
                        normalized.get("x") == null && normalized.get("y") == null
                                && normalized.get("dx") == null && normalized.get("dy") == null
                                ? "需要坐标或位移" : null)
                .setHandler(this::handleMove);
    }

    private Map<String, Object> handleMove(ToolRunContext ctx, Map<String, Object> params) {
        JSONObject document = requireDocument(ctx);
        JSONObject layer = documentService.resolveTarget(document, str(params.get("layerId")));
        if (params.get("x") != null) {
            layer.set("x", dv(params.get("x")));
        }
        if (params.get("y") != null) {
            layer.set("y", dv(params.get("y")));
        }
        if (params.get("dx") != null) {
            layer.set("x", dv(layer.get("x")) + dv(params.get("dx")));
        }
        if (params.get("dy") != null) {
            layer.set("y", dv(layer.get("y")) + dv(params.get("dy")));
        }
        return ToolResults.document(document);
    }

    // region 辅助

    private JSONObject requireDocument(ToolRunContext ctx) {
        PictureEditSession session = ctx.getSession();
        if (session == null) {
            throw new AgentToolException("此工具需要在编辑会话中使用");
        }
        JSONObject document = documentService.parseDocument(session);
        if (document == null) {
            throw new AgentToolException("画布文档缺失，请刷新会话");
        }
        // 深拷贝，失败不落库
        return JSONUtil.parseObj(document.toString());
    }

    private static String str(Object value) {
        return value == null ? null : StrUtil.blankToDefault(String.valueOf(value), null);
    }

    private static double dv(Object value) {
        return dv(value, 0.0);
    }

    private static double dv(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int[] ratioParts(String ratio) {
        String[] split = ratio.split(":");
        if (split.length != 2) {
            throw new AgentToolException("不支持的比例：" + ratio);
        }
        return new int[]{Integer.parseInt(split[0].trim()), Integer.parseInt(split[1].trim())};
    }

    // endregion
}

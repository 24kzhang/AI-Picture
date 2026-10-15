package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.agent.tool.AgentToolException;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates an editable text layer rather than baking lettering into the backing image. */
@Component
public class TextLayerTools implements AgentTool {
    @Resource private LayerDocumentService documentService;

    @Override
    public List<ToolSpec> specs() {
        return Collections.singletonList(new ToolSpec().setName("add_text_layer").setLabel("添加文字")
                .setDescription("在当前画布添加独立文字图层，支持多行；之后可改文字、移动、缩放、旋转、调整透明度与顺序。")
                .setSessionRequired(true).setQueued(false).setParams(Arrays.asList(
                        ParamField.string("text").setRequired(true).setMaxLength(500).setDescription("文字内容，支持换行"),
                        ParamField.string("name").setMaxLength(40).setDescription("图层名称，不填自动命名"),
                        ParamField.number("fontSize").setMin(8.0).setMax(400.0).setDefaultValue(32).setDescription("字号"),
                        ParamField.string("fill").setPattern("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$").setDefaultValue("#000000").setDescription("文字颜色"),
                        ParamField.number("x").setDescription("左上角横坐标，不填居中"),
                        ParamField.number("y").setDescription("左上角纵坐标，不填居中")))
                .setHandler(this::add));
    }

    private Map<String, Object> add(ToolRunContext context, Map<String, Object> params) {
        JSONObject document = documentService.parseDocument(context.getSession());
        if (document == null) throw new AgentToolException("画布文档缺失");
        int count = documentService.layers(document).size() + 1;
        String name = params.get("name") == null ? "文字 " + count : String.valueOf(params.get("name"));
        JSONObject layer = documentService.textLayer("text-" + UUID.randomUUID(), name,
                String.valueOf(params.get("text")), ((Number) params.get("fontSize")).doubleValue(),
                String.valueOf(params.get("fill")));
        layer.set("x", params.get("x") == null ? Math.max(0, (document.getInt("width", 1) - layer.getDouble("width")) / 2) : params.get("x"));
        layer.set("y", params.get("y") == null ? Math.max(0, (document.getInt("height", 1) - layer.getDouble("height")) / 2) : params.get("y"));
        documentService.layers(document).add(layer);
        return ToolResults.document(document);
    }
}

package com.zys.backend.agent.tool.impl;

import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * split_layers 与 promote_object_to_layer：保留工具定义和 UI 入口。
 * 第一阶段没有稳定的 Java/HTTP 分割 Provider，执行时返回明确失败原因，
 * 不作为验收主路径。
 */
@Component
public class LayerSplitTools implements AgentTool {

    private static final String UNSUPPORTED_MESSAGE =
            "当前部署没有可用的图像分割 Provider，此功能暂未开放；请先使用局部编辑或换背景工具完成任务。";

    @Override
    public List<ToolSpec> specs() {
        ToolSpec split = new ToolSpec()
                .setName("split_layers")
                .setLabel("拆分层")
                .setDescription("把画面拆成主体层与背景层（需要分割 Provider，第一阶段未开放）。")
                .setQueued(true)
                .setNeedsApproval(true)
                .setSessionRequired(true)
                .setParams(Collections.singletonList(
                        ParamField.bool("includeText").setDefaultValue(false)
                                .setDescription("是否把文字拆为独立文字层")))
                .setHandler((ctx, params) -> {
                    throw new AgentToolException(UNSUPPORTED_MESSAGE);
                });
        ToolSpec promote = new ToolSpec()
                .setName("promote_object_to_layer")
                .setLabel("提取对象为图层")
                .setDescription("把选中的物体提取为独立图层（需要分割 Provider，第一阶段未开放）。")
                .setQueued(true)
                .setNeedsApproval(true)
                .setSessionRequired(true)
                .setParams(Arrays.asList(
                        ParamField.string("name").setMaxLength(40).setDescription("新图层名字"),
                        ParamField.string("maskAssetId").setAgentHidden(true)
                                .setDescription("选区蒙版资产 id（服务端注入）"),
                        ParamField.integer("revision").setAgentHidden(true)
                                .setDescription("选区绑定的画布 revision（服务端注入）")))
                .setHandler((ctx, params) -> {
                    throw new AgentToolException(UNSUPPORTED_MESSAGE);
                });
        return Arrays.asList(split, promote);
    }
}

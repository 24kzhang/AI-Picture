package com.zys.backend.agent.tool;

import com.zys.backend.model.entity.PictureToolRun;

import java.util.Map;

/**
 * 工具业务处理器，只关心业务结果，状态流转由统一执行外壳负责。
 */
public interface AgentToolHandler {

    /**
     * 执行工具
     *
     * @param context 运行上下文（会话、任务、进度上报）
     * @param params  已通过校验并补齐默认值的参数
     * @return 结果 JSON 结构，可包含 assetIds/adoptAssetId/document/variants 等键
     * @throws Exception 执行失败
     */
    Map<String, Object> handle(ToolRunContext context, Map<String, Object> params) throws Exception;
}

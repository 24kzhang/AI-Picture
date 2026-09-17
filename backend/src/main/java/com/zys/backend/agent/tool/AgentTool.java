package com.zys.backend.agent.tool;

import java.util.List;

/**
 * 工具提供者。每个工具组件实现该接口并在 {@link #specs()} 中登记，
 * 注册表自动收集，同时对前端、规划模型与校验器生效。
 */
public interface AgentTool {

    /**
     * 该组件提供的工具定义（含参数声明与处理器）
     */
    List<ToolSpec> specs();
}

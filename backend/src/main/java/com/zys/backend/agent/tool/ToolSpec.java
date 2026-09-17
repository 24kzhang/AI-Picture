package com.zys.backend.agent.tool;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个工具的完整定义，前端、Planner、Validator、Executor 共用。
 *
 * <p>params 同时用于服务端校验和生成模型的函数签名，两者不会漂移。
 * paramClass 为可选的类型化参数载体说明；运行时校验统一走 params 声明。</p>
 */
@Data
@Accessors(chain = true)
public class ToolSpec {

    /**
     * 工具名，全局唯一
     */
    private String name;

    /**
     * 界面显示名
     */
    private String label;

    /**
     * 给规划模型和界面看的说明
     */
    private String description;

    /**
     * 是否进入 Redis Streams 异步执行；false 表示当前请求内同步执行
     */
    private boolean queued = true;

    /**
     * 多步计划中该工具是否必须用户确认
     */
    private boolean needsApproval;

    /**
     * 是否必须在编辑会话中使用
     */
    private boolean sessionRequired;

    /**
     * 类型化参数载体（可选，仅作为文档化说明）
     */
    private Class<?> paramClass;

    /**
     * 参数字段声明
     */
    private List<ParamField> params = new ArrayList<>();

    /**
     * 跨字段校验，返回错误信息，null 表示通过
     */
    private Function<Map<String, Object>, String> crossValidator;

    /**
     * 业务处理器
     */
    private transient AgentToolHandler handler;

    /**
     * 同一工具按参数细分说法，返回 null 则用 label
     */
    private transient Function<Map<String, Object>, String> detail;

    /**
     * 按参数取细分展示名
     */
    public String labelFor(Map<String, Object> params) {
        if (detail != null && params != null) {
            String refined = detail.apply(params);
            if (refined != null && !refined.isEmpty()) {
                return refined;
            }
        }
        return label;
    }
}

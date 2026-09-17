package com.zys.backend.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划步骤，状态流转与 picture_tool_run 保持一致。
 */
@Data
public class PlanStep {

    /**
     * 步骤 id，如 s1
     */
    private String id;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 归一化后的工具参数
     */
    private Map<String, Object> params = new LinkedHashMap<>();

    /**
     * 依赖的步骤 id 列表
     */
    private List<String> dependsOn = new ArrayList<>();

    /**
     * 关联的 picture_tool_run id，未创建任务时为空
     */
    private Long toolRunId;

    /**
     * 步骤状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status = "pending";

    /**
     * 该步骤是否需要用户单独确认
     */
    private boolean needsApproval;
}

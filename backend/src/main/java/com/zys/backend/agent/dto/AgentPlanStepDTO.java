package com.zys.backend.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 修图 Agent 计划步骤出参（PlanStepOut）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentPlanStepDTO {

    /**
     * 步骤 ID（如 s1）
     */
    private String id;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 中文说明
     */
    private String label;

    /**
     * 依赖步骤 ID 列表
     */
    @JsonProperty("depends_on")
    private List<String> dependsOn = new ArrayList<>();

    /**
     * 关联的 ToolRun ID
     */
    @JsonProperty("run_id")
    private String runId;

    /**
     * 步骤状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status;
}

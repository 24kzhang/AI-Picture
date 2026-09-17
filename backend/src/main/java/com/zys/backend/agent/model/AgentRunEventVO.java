package com.zys.backend.agent.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SSE 与 Pub/Sub 统一事件载荷。
 */
@Data
public class AgentRunEventVO {

    /**
     * 事件类型：snapshot/progress/plan/finished/canceled
     */
    private String event;

    private Long runId;

    private Long toolRunId;

    /**
     * run 或 toolRun 状态
     */
    private String status;

    private Integer progress;

    private String stage;

    /**
     * 计划快照（步骤 + 状态）
     */
    private List<PlanStep> plan;

    /**
     * 工具结果
     */
    private Map<String, Object> result;

    private String errorMessage;

    private Long timestamp;
}

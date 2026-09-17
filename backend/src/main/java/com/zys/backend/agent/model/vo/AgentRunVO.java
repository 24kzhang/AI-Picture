package com.zys.backend.agent.model.vo;

import com.zys.backend.agent.model.PlanStep;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 运行快照（GET run 与 SSE 首包）
 */
@Data
public class AgentRunVO {

    private Long id;

    private Long editSessionId;

    private Integer revision;

    private String goal;

    private String reply;

    private String status;

    private String errorMessage;

    /**
     * 计划步骤（含 toolRunId 与状态）
     */
    private List<PlanStep> plan;

    /**
     * 步骤对应的工具任务详情
     */
    private List<AgentToolRunVO> toolRuns;

    private Date createTime;

    private Date updateTime;
}

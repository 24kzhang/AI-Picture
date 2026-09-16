package com.zys.backend.agent.vo;

import com.zys.backend.agent.dto.AgentPlanStepDTO;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Agent 对话轮次 / 运行视图
 */
@Data
public class AgentTurnVO {

    /**
     * 云图库运行记录 id
     */
    private Long id;

    /**
     * Agent Run / Turn id
     */
    private String agentRunId;

    /**
     * 运行类型：TOOL/PLAN/BATCH/EXPORT
     */
    private String runType;

    /**
     * 运行状态
     */
    private String status;

    /**
     * 进度百分比
     */
    private Integer progress;

    /**
     * 当前阶段说明
     */
    private String stage;

    /**
     * 用户目标
     */
    private String goal;

    /**
     * Agent 回复
     */
    private String reply;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 计划步骤
     */
    private List<AgentPlanStepDTO> steps = new ArrayList<>();

    /**
     * 创建时间
     */
    private Date createTime;
}

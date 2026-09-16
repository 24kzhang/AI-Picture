package com.zys.backend.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 修图 Agent 对话轮次出参（TurnOut）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTurnDTO {

    /**
     * 轮次 ID（UUID，即 AgentRun ID）
     */
    private String id;

    /**
     * 规划时的画布修订号
     */
    private Integer revision;

    /**
     * 用户目标
     */
    private String goal;

    /**
     * Agent 回复
     */
    private String reply;

    /**
     * 状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 创建时间
     */
    @JsonProperty("created_at")
    private Date createdAt;

    /**
     * 计划步骤列表
     */
    private List<AgentPlanStepDTO> steps = new ArrayList<>();

    /**
     * 是否存在待确认的多步计划
     */
    public boolean needsConfirm() {
        return "waiting".equals(status);
    }
}

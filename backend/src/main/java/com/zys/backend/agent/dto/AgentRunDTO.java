package com.zys.backend.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修图 Agent 运行出参（RunOut）
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRunDTO {

    /**
     * Run ID（UUID）
     */
    private String id;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 状态：pending/waiting/queued/running/succeeded/failed/canceled
     */
    private String status;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 当前阶段说明
     */
    private String stage;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 提示词（生成类任务）
     */
    private String prompt;

    /**
     * 候选图列表
     */
    private List<AgentAssetDTO> candidates = new ArrayList<>();

    /**
     * 结果负载
     */
    private Map<String, Object> result = new LinkedHashMap<>();

    /**
     * 创建时间（透传用）
     */
    @JsonProperty("created_at")
    private Date createdAt;
}

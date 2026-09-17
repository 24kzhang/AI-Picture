package com.zys.backend.agent.model.vo;

import com.zys.backend.model.entity.PictureToolRun;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * 工具任务详情
 */
@Data
public class AgentToolRunVO {

    private Long id;

    private Long agentRunId;

    private String stepId;

    private String tool;

    private String toolLabel;

    private String status;

    private Integer progress;

    private String stage;

    private Map<String, Object> params;

    private Map<String, Object> result;

    private String errorMessage;

    private Integer retries;

    private Date startedAt;

    private Date finishedAt;

    public static AgentToolRunVO from(PictureToolRun run, String toolLabel) {
        AgentToolRunVO vo = new AgentToolRunVO();
        vo.setId(run.getId());
        vo.setAgentRunId(run.getAgentRunId());
        vo.setStepId(run.getStepId());
        vo.setTool(run.getTool());
        vo.setToolLabel(toolLabel);
        vo.setStatus(run.getStatus());
        vo.setProgress(run.getProgress());
        vo.setStage(run.getStage());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setRetries(run.getRetries());
        vo.setStartedAt(run.getStartedAt());
        vo.setFinishedAt(run.getFinishedAt());
        return vo;
    }
}

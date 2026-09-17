package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.model.vo.AgentRunVO;
import com.zys.backend.agent.model.vo.AgentToolRunVO;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureToolRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运行快照组装：run + 计划步骤 + 工具任务详情。
 * SSE 首包与 GET /agent-runs/{runId} 共用。
 */
@Slf4j
@Service
public class AgentRunSnapshotService {

    @Resource
    private ToolRunService toolRunService;

    @Resource
    private ToolRegistry toolRegistry;

    /**
     * 组装完整运行快照
     */
    public AgentRunVO build(PictureAgentRun run) {
        AgentRunVO vo = new AgentRunVO();
        vo.setId(run.getId());
        vo.setEditSessionId(run.getEditSessionId());
        vo.setRevision(run.getRevision());
        vo.setGoal(run.getGoal());
        vo.setReply(run.getReply());
        vo.setStatus(run.getStatus());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setCreateTime(run.getCreateTime());
        vo.setUpdateTime(run.getUpdateTime());
        vo.setPlan(stepsOf(run));
        List<AgentToolRunVO> toolRuns = new ArrayList<>();
        for (PictureToolRun toolRun : toolRunService.listByAgentRun(run.getId())) {
            AgentToolRunVO toolVO = AgentToolRunVO.from(toolRun, labelOf(toolRun));
            if (StrUtil.isNotBlank(toolRun.getParamsJson())) {
                try {
                    toolVO.setParams(JSONUtil.toBean(toolRun.getParamsJson(), Map.class));
                } catch (Exception ignored) {
                    // 参数损坏时保留其余字段
                }
            }
            if (StrUtil.isNotBlank(toolRun.getResultJson())) {
                try {
                    toolVO.setResult(JSONUtil.toBean(toolRun.getResultJson(), Map.class));
                } catch (Exception ignored) {
                    // 结果损坏时保留其余字段
                }
            }
            toolRuns.add(toolVO);
        }
        vo.setToolRuns(toolRuns);
        return vo;
    }

    /**
     * 读取运行计划步骤
     */
    public List<PlanStep> stepsOf(PictureAgentRun run) {
        if (run == null || StrUtil.isBlank(run.getPlanJson())) {
            return new ArrayList<>();
        }
        try {
            List<PlanStep> steps = JSONUtil.toList(run.getPlanJson(), PlanStep.class);
            return steps == null ? new ArrayList<>() : steps;
        } catch (Exception e) {
            log.warn("计划 JSON 解析失败，runId={}", run.getId());
            return new ArrayList<>();
        }
    }

    private String labelOf(PictureToolRun toolRun) {
        try {
            ToolSpec spec = toolRegistry.specOf(toolRun.getTool());
            Map<String, Object> params = null;
            if (StrUtil.isNotBlank(toolRun.getParamsJson())) {
                params = JSONUtil.toBean(toolRun.getParamsJson(), Map.class);
            }
            return spec.labelFor(params);
        } catch (Exception e) {
            return toolRun.getTool();
        }
    }
}

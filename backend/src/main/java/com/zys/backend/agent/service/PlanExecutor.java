package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.mapper.PictureAgentRunMapper;
import com.zys.backend.mapper.PictureToolRunMapper;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureToolRun;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentRunStatusEnum;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 计划执行器：按 DAG 推进 ready 步骤，单步直接执行，多步先进入 waiting。
 * 同步工具当前请求内执行；异步工具写 MySQL 后进入 Redis Streams。
 */
@Slf4j
@Service
public class PlanExecutor {

    @Resource
    private PictureAgentRunMapper agentRunMapper;

    @Resource
    private PictureToolRunMapper toolRunMapper;

    @Resource
    private PlanValidator planValidator;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    @Lazy
    private ToolRunService toolRunService;

    @Resource
    private AgentQueueService queueService;

    @Resource
    private AgentEventPublisher eventPublisher;

    @Resource
    @Lazy
    private AgentRunSnapshotService snapshotService;

    /**
     * 规划完成后启动：无工具纯文本已在上游落 succeeded；单步直接执行；多步等待确认
     */
    public void start(PictureAgentRun run, List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        if (!planValidator.needsConfirm(steps)) {
            updateRunStatus(run, AgentRunStatusEnum.RUNNING.getValue(), null);
            publishPlan(run.getId(), steps);
            startReadySteps(run, steps);
        } else {
            updateRunStatus(run, AgentRunStatusEnum.WAITING.getValue(), null);
            publishPlan(run.getId(), steps);
        }
    }

    /**
     * 用户确认多步计划
     */
    public PictureAgentRun confirm(Long runId, User loginUser) {
        PictureAgentRun run = requireRun(runId);
        checkOperator(run, loginUser);
        if (!AgentRunStatusEnum.WAITING.getValue().equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前运行不在等待确认状态");
        }
        List<PlanStep> steps = parseSteps(run);
        updateRunStatus(run, AgentRunStatusEnum.RUNNING.getValue(), null);
        startReadySteps(run, steps);
        return run;
    }

    /**
     * 取消计划：只取消 pending/waiting/queued 步骤；running 长任务标记取消意图
     */
    public PictureAgentRun cancel(Long runId, User loginUser) {
        PictureAgentRun run = requireRun(runId);
        checkOperator(run, loginUser);
        if (AgentRunStatusEnum.isTerminal(run.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "运行已结束，无法取消");
        }
        List<PlanStep> steps = parseSteps(run);
        for (PlanStep step : steps) {
            if (step.getToolRunId() == null) {
                if (isOpen(step.getStatus())) {
                    step.setStatus(ToolRunStatusEnum.CANCELED.getValue());
                }
                continue;
            }
            PictureToolRun toolRun = toolRunMapper.selectById(step.getToolRunId());
            if (toolRun == null || ToolRunStatusEnum.isTerminal(toolRun.getStatus())) {
                if (toolRun != null) {
                    step.setStatus(toolRun.getStatus());
                }
                continue;
            }
            if (ToolRunStatusEnum.isCancelable(toolRun.getStatus())) {
                toolRunService.clearCancelIntent(toolRun.getId());
                toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                        .eq(PictureToolRun::getId, toolRun.getId())
                        .in(PictureToolRun::getStatus,
                                ToolRunStatusEnum.PENDING.getValue(),
                                ToolRunStatusEnum.WAITING.getValue(),
                                ToolRunStatusEnum.QUEUED.getValue())
                        .set(PictureToolRun::getStatus, ToolRunStatusEnum.CANCELED.getValue())
                        .set(PictureToolRun::getErrorMessage, "用户取消")
                        .set(PictureToolRun::getFinishedAt, new Date()));
                step.setStatus(ToolRunStatusEnum.CANCELED.getValue());
            } else {
                // running：只标记取消意图，结果回写前再次检查状态
                toolRunService.markCancelIntent(toolRun.getId());
            }
        }
        saveSteps(run, steps);
        updateRunStatus(run, AgentRunStatusEnum.CANCELED.getValue(), "用户取消");
        publishPlan(runId, steps);
        publishFinished(run);
        return run;
    }

    /**
     * 重试失败/取消的步骤：已成功步骤保留结果，未成功步骤重置为 pending
     */
    public PictureAgentRun retry(Long runId, User loginUser) {
        PictureAgentRun run = requireRun(runId);
        checkOperator(run, loginUser);
        String status = run.getStatus();
        if (!AgentRunStatusEnum.FAILED.getValue().equals(status)
                && !AgentRunStatusEnum.CANCELED.getValue().equals(status)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅失败或已取消的运行可重试");
        }
        List<PlanStep> steps = parseSteps(run);
        List<PlanStep> reset = new ArrayList<>();
        for (PlanStep step : steps) {
            if (ToolRunStatusEnum.SUCCEEDED.getValue().equals(step.getStatus())) {
                continue;
            }
            if (step.getToolRunId() != null) {
                toolRunService.clearCancelIntent(step.getToolRunId());
            }
            step.setToolRunId(null);
            step.setStatus(ToolRunStatusEnum.PENDING.getValue());
            reset.add(step);
        }
        if (reset.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有需要重试的步骤");
        }
        saveSteps(run, steps);
        updateRunStatus(run, AgentRunStatusEnum.RUNNING.getValue(), null);
        publishPlan(runId, steps);
        startReadySteps(run, steps);
        return run;
    }

    /**
     * 工具任务终态回调：同步计划内步骤状态并推进/收敛整轮运行
     */
    public void onToolRunFinished(Long toolRunId) {
        PictureToolRun toolRun = toolRunMapper.selectById(toolRunId);
        if (toolRun == null || toolRun.getAgentRunId() == null) {
            return;
        }
        PictureAgentRun run = agentRunMapper.selectById(toolRun.getAgentRunId());
        if (run == null) {
            return;
        }
        List<PlanStep> steps = parseSteps(run);
        boolean matched = false;
        for (PlanStep step : steps) {
            if (toolRunId.equals(step.getToolRunId())) {
                step.setStatus(toolRun.getStatus());
                matched = true;
            }
        }
        if (!matched) {
            return;
        }
        saveSteps(run, steps);
        toolRunService.clearCancelIntent(toolRunId);

        boolean anyOpen = false;
        boolean anyFailed = false;
        boolean anyCanceled = false;
        boolean allDone = true;
        StringBuilder error = new StringBuilder();
        for (PlanStep step : steps) {
            String status = step.getStatus();
            if (ToolRunStatusEnum.isTerminal(status)) {
                if (ToolRunStatusEnum.FAILED.getValue().equals(status)) {
                    anyFailed = true;
                    if (error.length() == 0 && StrUtil.isNotBlank(stepError(step))) {
                        error.append(stepError(step));
                    }
                } else if (ToolRunStatusEnum.CANCELED.getValue().equals(status)) {
                    anyCanceled = true;
                }
            } else {
                allDone = false;
                if (isOpen(status)) {
                    anyOpen = true;
                }
            }
        }
        if (!allDone && anyOpen && !anyFailed) {
            // 继续推进就绪步骤
            String runStatus = run.getStatus();
            if (AgentRunStatusEnum.RUNNING.getValue().equals(runStatus)) {
                startReadySteps(run, steps);
            }
            return;
        }
        if (!allDone && (anyFailed || anyCanceled)) {
            // 失败阻断：未启动步骤置为取消语义由 cancel 负责，这里直接收敛运行
        }
        if (anyFailed) {
            updateRunStatus(run, AgentRunStatusEnum.FAILED.getValue(),
                    error.length() > 0 ? error.toString() : "存在失败步骤");
        } else if (!allDone) {
            // 仍有非就绪步骤被依赖阻断
            updateRunStatus(run, AgentRunStatusEnum.FAILED.getValue(), "计划存在无法推进的步骤");
        } else if (anyCanceled) {
            updateRunStatus(run, AgentRunStatusEnum.CANCELED.getValue(), "部分步骤被取消");
        } else {
            updateRunStatus(run, AgentRunStatusEnum.SUCCEEDED.getValue(), null);
        }
        publishPlan(run.getId(), steps);
        publishFinished(run);
    }

    /**
     * 启动全部就绪步骤
     */
    private void startReadySteps(PictureAgentRun run, List<PlanStep> steps) {
        List<PlanStep> readySteps = planValidator.ready(steps);
        if (readySteps.isEmpty()) {
            return;
        }
        for (PlanStep step : readySteps) {
            ToolSpec spec = toolRegistry.specOf(step.getTool());
            PictureToolRun toolRun = toolRunService.createToolRun(run, step,
                    spec.isQueued() ? ToolRunStatusEnum.QUEUED.getValue() : ToolRunStatusEnum.PENDING.getValue());
            step.setToolRunId(toolRun.getId());
            step.setStatus(toolRun.getStatus());
            publishStepEvent(run, toolRun);
        }
        saveSteps(run, steps);
        List<PlanStep> refresh = readySteps;
        for (PlanStep step : refresh) {
            ToolSpec spec = toolRegistry.specOf(step.getTool());
            if (spec.isQueued()) {
                queueService.enqueue(step.getToolRunId());
            } else {
                toolRunService.executeInline(step.getToolRunId());
                // 同步步骤执行完毕后递归推进（onToolRunFinished 内已处理），
                // 这里重新加载计划，避免使用过期的内存状态
                PictureAgentRun latest = agentRunMapper.selectById(run.getId());
                if (latest != null) {
                    run.setStatus(latest.getStatus());
                    steps = parseSteps(latest);
                }
            }
        }
    }

    private String stepError(PlanStep step) {
        if (step.getToolRunId() == null) {
            return null;
        }
        PictureToolRun toolRun = toolRunMapper.selectById(step.getToolRunId());
        return toolRun == null ? null : toolRun.getErrorMessage();
    }

    private boolean isOpen(String status) {
        return ToolRunStatusEnum.PENDING.getValue().equals(status)
                || ToolRunStatusEnum.WAITING.getValue().equals(status)
                || ToolRunStatusEnum.QUEUED.getValue().equals(status)
                || ToolRunStatusEnum.RUNNING.getValue().equals(status);
    }

    private void updateRunStatus(PictureAgentRun run, String status, String errorMessage) {
        agentRunMapper.update(null, Wrappers.<PictureAgentRun>lambdaUpdate()
                .eq(PictureAgentRun::getId, run.getId())
                .set(PictureAgentRun::getStatus, status)
                .set(errorMessage != null, PictureAgentRun::getErrorMessage, errorMessage));
        run.setStatus(status);
        if (errorMessage != null) {
            run.setErrorMessage(errorMessage);
        }
    }

    private void publishStepEvent(PictureAgentRun run, PictureToolRun toolRun) {
        AgentRunEventVO event = new AgentRunEventVO();
        event.setEvent("plan");
        event.setRunId(run.getId());
        event.setToolRunId(toolRun.getId());
        event.setStatus(toolRun.getStatus());
        event.setProgress(toolRun.getProgress());
        event.setStage(toolRun.getStage());
        eventPublisher.publish(run.getId(), event);
    }

    private void publishPlan(Long runId, List<PlanStep> steps) {
        AgentRunEventVO event = new AgentRunEventVO();
        event.setEvent("plan");
        event.setRunId(runId);
        event.setPlan(steps);
        eventPublisher.publish(runId, event);
    }

    private void publishFinished(PictureAgentRun run) {
        AgentRunEventVO event = new AgentRunEventVO();
        event.setEvent(AgentRunStatusEnum.CANCELED.getValue().equals(run.getStatus()) ? "canceled" : "finished");
        event.setRunId(run.getId());
        event.setStatus(run.getStatus());
        event.setErrorMessage(run.getErrorMessage());
        event.setPlan(snapshotService == null ? null : snapshotService.stepsOf(run));
        eventPublisher.publish(run.getId(), event);
    }

    public PictureAgentRun requireRun(Long runId) {
        PictureAgentRun run = agentRunMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "运行记录不存在");
        }
        return run;
    }

    private void checkOperator(PictureAgentRun run, User loginUser) {
        if (loginUser == null || !loginUser.getId().equals(run.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只有发起人可以操作该运行");
        }
    }

    public List<PlanStep> parseSteps(PictureAgentRun run) {
        if (run == null || StrUtil.isBlank(run.getPlanJson())) {
            return new ArrayList<>();
        }
        try {
            List<PlanStep> steps = JSONUtil.toList(run.getPlanJson(), PlanStep.class);
            return steps == null ? new ArrayList<>() : steps;
        } catch (Exception e) {
            log.error("计划 JSON 解析失败，runId={}", run.getId(), e);
            return new ArrayList<>();
        }
    }

    public void saveSteps(PictureAgentRun run, List<PlanStep> steps) {
        String json = JSONUtil.toJsonStr(steps);
        agentRunMapper.update(null, Wrappers.<PictureAgentRun>lambdaUpdate()
                .eq(PictureAgentRun::getId, run.getId())
                .set(PictureAgentRun::getPlanJson, json));
        run.setPlanJson(json);
    }
}

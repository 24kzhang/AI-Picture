package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.AgentToolHandler;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.mapper.PictureAgentRunMapper;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.mapper.PictureToolRunMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureToolRun;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工具任务执行外壳：创建记录、状态流转、失败兜底、幂等跳过终态任务。
 * 具体工具只返回业务结果。
 */
@Slf4j
@Service
public class ToolRunService {

    private static final String CANCEL_INTENT_PREFIX = "gallery:agent:cancel-intent:";

    @Resource
    private PictureToolRunMapper toolRunMapper;

    @Resource
    private PictureAgentRunMapper agentRunMapper;

    @Resource
    private PictureEditSessionMapper sessionMapper;

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private AgentEventPublisher eventPublisher;

    @Resource
    private SelectionService selectionService;

    @Resource
    private LayerDocumentService layerDocumentService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Lazy
    private PlanExecutor planExecutor;

    /**
     * 为计划步骤创建工具任务记录
     */
    public PictureToolRun createToolRun(PictureAgentRun agentRun, PlanStep step, String status) {
        PictureToolRun run = new PictureToolRun();
        run.setAgentRunId(agentRun.getId());
        run.setEditSessionId(agentRun.getEditSessionId());
        run.setUserId(agentRun.getUserId());
        run.setStepId(step.getId());
        run.setTool(step.getTool());
        run.setStatus(StrUtil.blankToDefault(status, ToolRunStatusEnum.PENDING.getValue()));
        run.setProgress(0);
        run.setParamsJson(JSONUtil.toJsonStr(step.getParams()));
        run.setRetries(0);
        toolRunMapper.insert(run);
        return run;
    }

    /**
     * 同步工具：当前请求线程内执行
     */
    public void executeInline(Long toolRunId) {
        execute(toolRunId);
    }

    /**
     * Redis Streams 消费入口：终态幂等跳过
     */
    public void executeQueued(Long toolRunId) {
        execute(toolRunId);
    }

    private void execute(Long toolRunId) {
        PictureToolRun run = toolRunMapper.selectById(toolRunId);
        if (run == null) {
            log.warn("工具任务不存在，跳过，toolRunId={}", toolRunId);
            return;
        }
        if (ToolRunStatusEnum.isTerminal(run.getStatus())) {
            // 重复消费直接跳过终态任务
            return;
        }
        // 乐观抢占：仅 pending/waiting/queued 可进入 running，防止多消费者并发重复执行
        boolean claimed = toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                .eq(PictureToolRun::getId, toolRunId)
                .in(PictureToolRun::getStatus,
                        ToolRunStatusEnum.PENDING.getValue(),
                        ToolRunStatusEnum.WAITING.getValue(),
                        ToolRunStatusEnum.QUEUED.getValue())
                .set(PictureToolRun::getStatus, ToolRunStatusEnum.RUNNING.getValue())
                .set(PictureToolRun::getStartedAt, new Date())
                .set(PictureToolRun::getStage, "开始执行")) > 0;
        if (!claimed) {
            return;
        }
        run.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        publishToolEvent(run, "progress", null);

        ToolSpec spec;
        try {
            spec = toolRegistry.specOf(run.getTool());
            Map<String, Object> params = parseParams(run);
            params = toolRegistry.validateParams(run.getTool(), params);
            injectHiddenParams(run, spec, params);
            ToolRunContext context = buildContext(run);
            AgentToolHandler handler = spec.getHandler();
            Map<String, Object> result = handler.handle(context, params);
            // 结果回写前再次检查取消意图与数据库状态
            if (isCancelRequested(run.getId())) {
                markCanceled(run, "任务已按用户要求取消");
                return;
            }
            PictureToolRun latest = toolRunMapper.selectById(run.getId());
            if (latest != null && ToolRunStatusEnum.CANCELED.getValue().equals(latest.getStatus())) {
                log.info("任务已被取消，丢弃结果，toolRunId={}", run.getId());
                return;
            }
            recordResult(run, result);
            markSucceeded(run, result);
        } catch (AgentToolException e) {
            markFailed(run, e.getMessage());
        } catch (BusinessException e) {
            markFailed(run, StrUtil.blankToDefault(e.getMessage(), "操作失败"));
        } catch (Exception e) {
            log.error("工具执行异常，tool={}，toolRunId={}", run.getTool(), run.getId(), e);
            markFailed(run, "执行失败，请重试");
        } finally {
            planExecutor.onToolRunFinished(run.getId());
        }
    }

    /**
     * 上报进度：落库 + SSE 推送
     */
    public void report(PictureToolRun run, int progress, String stage) {
        toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                .eq(PictureToolRun::getId, run.getId())
                .set(PictureToolRun::getProgress, Math.max(0, Math.min(100, progress)))
                .set(PictureToolRun::getStage, stage));
        run.setProgress(progress);
        run.setStage(stage);
        publishToolEvent(run, "progress", null);
    }

    /**
     * 成功终态
     */
    private void markSucceeded(PictureToolRun run, Map<String, Object> result) {
        toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                .eq(PictureToolRun::getId, run.getId())
                .notIn(PictureToolRun::getStatus,
                        ToolRunStatusEnum.CANCELED.getValue(),
                        ToolRunStatusEnum.FAILED.getValue())
                .set(PictureToolRun::getStatus, ToolRunStatusEnum.SUCCEEDED.getValue())
                .set(PictureToolRun::getProgress, 100)
                .set(PictureToolRun::getStage, "完成")
                .set(PictureToolRun::getResultJson, result == null ? null : JSONUtil.toJsonStr(result))
                .set(PictureToolRun::getFinishedAt, new Date()));
        run.setStatus(ToolRunStatusEnum.SUCCEEDED.getValue());
        run.setProgress(100);
        publishToolEvent(run, "progress", result);
    }

    /**
     * 失败终态
     */
    private void markFailed(PictureToolRun run, String message) {
        String error = StrUtil.blankToDefault(message, "执行失败");
        toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                .eq(PictureToolRun::getId, run.getId())
                .notIn(PictureToolRun::getStatus,
                        ToolRunStatusEnum.CANCELED.getValue(),
                        ToolRunStatusEnum.SUCCEEDED.getValue())
                .set(PictureToolRun::getStatus, ToolRunStatusEnum.FAILED.getValue())
                .set(PictureToolRun::getErrorMessage, error)
                .set(PictureToolRun::getFinishedAt, new Date()));
        run.setStatus(ToolRunStatusEnum.FAILED.getValue());
        run.setErrorMessage(error);
        publishToolEvent(run, "progress", null);
    }

    /**
     * 取消终态（运行中任务收到取消意图后由执行线程落盘）
     */
    private void markCanceled(PictureToolRun run, String message) {
        toolRunMapper.update(null, Wrappers.<PictureToolRun>lambdaUpdate()
                .eq(PictureToolRun::getId, run.getId())
                .notIn(PictureToolRun::getStatus,
                        ToolRunStatusEnum.SUCCEEDED.getValue(),
                        ToolRunStatusEnum.FAILED.getValue())
                .set(PictureToolRun::getStatus, ToolRunStatusEnum.CANCELED.getValue())
                .set(PictureToolRun::getErrorMessage, message)
                .set(PictureToolRun::getFinishedAt, new Date()));
        run.setStatus(ToolRunStatusEnum.CANCELED.getValue());
        publishToolEvent(run, "canceled", null);
    }

    /**
     * 队列超限兜底：仅未终态任务可标记失败
     */
    public void failIfOpen(Long toolRunId, String message) {
        PictureToolRun run = toolRunMapper.selectById(toolRunId);
        if (run == null || ToolRunStatusEnum.isTerminal(run.getStatus())) {
            return;
        }
        markFailed(run, message);
        planExecutor.onToolRunFinished(toolRunId);
    }

    /**
     * 标记取消意图：running 长任务在结果回写前检查
     */
    public void markCancelIntent(Long toolRunId) {
        stringRedisTemplate.opsForValue().set(CANCEL_INTENT_PREFIX + toolRunId, "1", 24, TimeUnit.HOURS);
    }

    public boolean isCancelRequested(Long toolRunId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(CANCEL_INTENT_PREFIX + toolRunId));
    }

    public void clearCancelIntent(Long toolRunId) {
        stringRedisTemplate.delete(CANCEL_INTENT_PREFIX + toolRunId);
    }

    /**
     * 工具结果回写：画布变更走 LayerDocumentService（revision+1、历史、选区失效）
     */
    private void recordResult(PictureToolRun run, Map<String, Object> result) {
        if (run.getEditSessionId() == null || result == null) {
            return;
        }
        PictureEditSession session = sessionMapper.selectById(run.getEditSessionId());
        if (session == null) {
            return;
        }
        try {
            boolean changed = layerDocumentService.applyToolResult(session, run, result);
            if (changed) {
                log.debug("画布已更新，sessionId={}，toolRunId={}，revision={}",
                        session.getId(), run.getId(), session.getRevision());
            }
        } catch (Exception e) {
            log.error("工具结果回写画布失败，toolRunId={}", run.getId(), e);
            throw e;
        }
    }

    private ToolRunContext buildContext(PictureToolRun run) {
        ToolRunContext context = new ToolRunContext();
        context.setToolRun(run);
        context.setReporter((progress, stage) -> report(run, progress, stage));
        if (run.getEditSessionId() != null) {
            PictureEditSession session = sessionMapper.selectById(run.getEditSessionId());
            context.setSession(session);
            if (session != null) {
                Picture picture = pictureMapper.selectById(session.getPictureId());
                context.setPicture(picture);
            }
        }
        return context;
    }

    /**
     * 服务端注入对模型隐藏的参数：maskAssetId、revision 从当前有效选区填入
     */
    private void injectHiddenParams(PictureToolRun run, ToolSpec spec, Map<String, Object> params) {
        boolean needSelection = false;
        for (ParamField field : spec.getParams()) {
            if (field.isAgentHidden() && ("maskAssetId".equals(field.getName()) || "revision".equals(field.getName()))) {
                needSelection = true;
                break;
            }
        }
        if (!needSelection) {
            return;
        }
        if (run.getEditSessionId() == null) {
            return;
        }
        PictureEditSession session = sessionMapper.selectById(run.getEditSessionId());
        if (session == null) {
            return;
        }
        // 先校验显式传入的 revision（界面直调可能携带）
        selectionService.checkRevision(session, params);
        JSONObject selection = selectionService.getValidSelection(session);
        if (selection == null) {
            params.remove("maskAssetId");
            params.put("revision", session.getRevision());
            return;
        }
        Long maskAssetId = selection.getLong("maskAssetId");
        if (maskAssetId != null) {
            params.put("maskAssetId", maskAssetId);
        }
        params.put("revision", selection.getInt("revision", session.getRevision()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(PictureToolRun run) {
        if (StrUtil.isBlank(run.getParamsJson())) {
            return new HashMap<>();
        }
        try {
            return JSONUtil.toBean(run.getParamsJson(), Map.class);
        } catch (Exception e) {
            throw new AgentToolException("参数 JSON 解析失败");
        }
    }

    private void publishToolEvent(PictureToolRun run, String event, Map<String, Object> result) {
        AgentRunEventVO payload = new AgentRunEventVO();
        payload.setEvent(event);
        payload.setRunId(run.getAgentRunId());
        payload.setToolRunId(run.getId());
        payload.setStatus(run.getStatus());
        payload.setProgress(run.getProgress());
        payload.setStage(run.getStage());
        payload.setResult(result);
        payload.setErrorMessage(run.getErrorMessage());
        eventPublisher.publish(run.getAgentRunId(), payload);
    }

    /**
     * 查询某轮运行下的全部工具任务
     */
    public List<PictureToolRun> listByAgentRun(Long agentRunId) {
        return toolRunMapper.selectList(Wrappers.<PictureToolRun>lambdaQuery()
                .eq(PictureToolRun::getAgentRunId, agentRunId)
                .orderByAsc(PictureToolRun::getCreateTime));
    }

    public PictureToolRun getById(Long toolRunId) {
        return toolRunMapper.selectById(toolRunId);
    }

    public PictureAgentRun getAgentRun(Long runId) {
        return agentRunMapper.selectById(runId);
    }
}

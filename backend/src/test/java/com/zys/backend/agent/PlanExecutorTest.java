package com.zys.backend.agent;

import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.service.AgentEventPublisher;
import com.zys.backend.agent.service.AgentQueueService;
import com.zys.backend.agent.service.AgentRunSnapshotService;
import com.zys.backend.agent.service.PlanExecutor;
import com.zys.backend.agent.service.PlanValidator;
import com.zys.backend.agent.service.ToolRunService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolHandler;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.mapper.PictureAgentRunMapper;
import com.zys.backend.mapper.PictureToolRunMapper;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureToolRun;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentRunStatusEnum;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import com.zys.backend.agent.support.AgentTestSupport;
import com.zys.backend.model.entity.PictureAgentRun;
import com.zys.backend.model.entity.PictureToolRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanExecutor：单步直跑、多步等待、确认后推进、失败阻断、重试失败步骤
 */
class PlanExecutorTest {

    private PlanExecutor executor;
    private PictureAgentRunMapper agentRunMapper;
    private PictureToolRunMapper toolRunMapper;
    private ToolRunService toolRunService;
    private AgentQueueService queueService;
    private AgentEventPublisher eventPublisher;

    private User user;

    @BeforeAll
    static void initEntities() {
        AgentTestSupport.initTableInfo(PictureAgentRun.class, PictureToolRun.class);
    }

    @BeforeEach
    void setUp() {
        AgentToolHandler noop = (ctx, params) -> Collections.emptyMap();
        ToolSpec syncSpec = new ToolSpec()
                .setName("flip_layer")
                .setLabel("翻转")
                .setQueued(false)
                .setSessionRequired(false)
                .setHandler(noop);
        ToolSpec asyncSpec = new ToolSpec()
                .setName("replace_background")
                .setLabel("换背景")
                .setQueued(true)
                .setSessionRequired(false)
                .setHandler(noop);
        ToolRegistry registry = new ToolRegistry();
        AgentTool tool = () -> Arrays.asList(syncSpec, asyncSpec);
        ReflectionTestUtils.setField(registry, "tools", Collections.singletonList(tool));
        registry.init();

        PlanValidator validator = new PlanValidator();
        ReflectionTestUtils.setField(validator, "toolRegistry", registry);

        executor = new PlanExecutor();
        agentRunMapper = mock(PictureAgentRunMapper.class);
        toolRunMapper = mock(PictureToolRunMapper.class);
        toolRunService = mock(ToolRunService.class);
        queueService = mock(AgentQueueService.class);
        eventPublisher = mock(AgentEventPublisher.class);
        ReflectionTestUtils.setField(executor, "agentRunMapper", agentRunMapper);
        ReflectionTestUtils.setField(executor, "toolRunMapper", toolRunMapper);
        ReflectionTestUtils.setField(executor, "planValidator", validator);
        ReflectionTestUtils.setField(executor, "toolRegistry", registry);
        ReflectionTestUtils.setField(executor, "toolRunService", toolRunService);
        ReflectionTestUtils.setField(executor, "queueService", queueService);
        ReflectionTestUtils.setField(executor, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(executor, "snapshotService", mock(AgentRunSnapshotService.class));

        user = new User();
        user.setId(2L);
        user.setUserName("Alice");

        when(toolRunService.createToolRun(any(), any(), anyString())).thenAnswer(invocation -> {
            PlanStep step = invocation.getArgument(1);
            PictureToolRun run = new PictureToolRun();
            run.setId(System.nanoTime());
            run.setStepId(step.getId());
            run.setTool(step.getTool());
            run.setStatus(invocation.getArgument(2));
            return run;
        });
    }

    private PlanStep step(String id, String tool, String... dependsOn) {
        PlanStep step = new PlanStep();
        step.setId(id);
        step.setTool(tool);
        step.setStatus(ToolRunStatusEnum.PENDING.getValue());
        step.setDependsOn(new ArrayList<>(Arrays.asList(dependsOn)));
        return step;
    }

    private PictureAgentRun run(List<PlanStep> steps) {
        PictureAgentRun run = new PictureAgentRun();
        run.setId(1001L);
        run.setUserId(2L);
        run.setEditSessionId(9L);
        run.setRevision(1);
        run.setStatus(AgentRunStatusEnum.PLANNING.getValue());
        run.setPlanJson(JSONUtil.toJsonStr(steps));
        return run;
    }

    @Test
    void singleStepRunsImmediately() {
        List<PlanStep> steps = Collections.singletonList(step("s1", "replace_background"));
        PictureAgentRun run = run(steps);
        executor.start(run, steps);
        assertEquals(AgentRunStatusEnum.RUNNING.getValue(), run.getStatus());
        verify(toolRunService).createToolRun(any(), any(),
                org.mockito.ArgumentMatchers.eq(ToolRunStatusEnum.QUEUED.getValue()));
        verify(queueService).enqueue(anyLong());
    }

    @Test
    void singleSyncStepExecutesInline() {
        List<PlanStep> steps = Collections.singletonList(step("s1", "flip_layer"));
        PictureAgentRun run = run(steps);
        when(agentRunMapper.selectById(1001L)).thenReturn(run);
        executor.start(run, steps);
        verify(toolRunService).executeInline(anyLong());
        verify(queueService, never()).enqueue(anyLong());
    }

    @Test
    void multiStepWaitsForConfirmation() {
        List<PlanStep> steps = Arrays.asList(
                step("s1", "replace_background"),
                step("s2", "flip_layer", "s1"));
        PictureAgentRun run = run(steps);
        executor.start(run, steps);
        assertEquals(AgentRunStatusEnum.WAITING.getValue(), run.getStatus());
        verify(toolRunService, never()).createToolRun(any(), any(), anyString());
        verify(queueService, never()).enqueue(anyLong());
    }

    @Test
    void confirmDispatchesOnlyReadySteps() {
        List<PlanStep> steps = Arrays.asList(
                step("s1", "replace_background"),
                step("s2", "flip_layer", "s1"));
        PictureAgentRun run = run(steps);
        run.setStatus(AgentRunStatusEnum.WAITING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        executor.confirm(1001L, user);

        assertEquals(AgentRunStatusEnum.RUNNING.getValue(), run.getStatus());
        ArgumentCaptor<PlanStep> captor = ArgumentCaptor.forClass(PlanStep.class);
        verify(toolRunService).createToolRun(any(), captor.capture(), anyString());
        assertEquals("s1", captor.getValue().getId());
        verify(queueService).enqueue(anyLong());
    }

    @Test
    void confirmRejectedWhenNotWaiting() {
        PictureAgentRun run = run(Collections.singletonList(step("s1", "flip_layer")));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);
        assertThrows(BusinessException.class, () -> executor.confirm(1001L, user));
    }

    @Test
    void onlyInitiatorCanOperateRun() {
        PictureAgentRun run = run(Collections.singletonList(step("s1", "flip_layer")));
        run.setStatus(AgentRunStatusEnum.WAITING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);
        User bob = new User();
        bob.setId(3L);
        assertThrows(BusinessException.class, () -> executor.confirm(1001L, bob));
    }

    @Test
    void toolFailureBlocksRunAndSettlesFailed() {
        PlanStep first = step("s1", "replace_background");
        first.setToolRunId(2001L);
        first.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        PlanStep second = step("s2", "flip_layer", "s1");
        PictureAgentRun run = run(Arrays.asList(first, second));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        PictureToolRun failedToolRun = new PictureToolRun();
        failedToolRun.setId(2001L);
        failedToolRun.setAgentRunId(1001L);
        failedToolRun.setStatus(ToolRunStatusEnum.FAILED.getValue());
        failedToolRun.setErrorMessage("生成失败");
        when(toolRunMapper.selectById(2001L)).thenReturn(failedToolRun);

        executor.onToolRunFinished(2001L);

        assertEquals(AgentRunStatusEnum.FAILED.getValue(), run.getStatus());
        // 失败阻断：第二步不会被派发
        verify(toolRunService, never()).createToolRun(any(), any(), anyString());
    }

    @Test
    void toolSuccessAdvancesDependentStep() {
        PlanStep first = step("s1", "replace_background");
        first.setToolRunId(2001L);
        first.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        PlanStep second = step("s2", "flip_layer", "s1");
        PictureAgentRun run = run(Arrays.asList(first, second));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        PictureToolRun okToolRun = new PictureToolRun();
        okToolRun.setId(2001L);
        okToolRun.setAgentRunId(1001L);
        okToolRun.setStatus(ToolRunStatusEnum.SUCCEEDED.getValue());
        when(toolRunMapper.selectById(2001L)).thenReturn(okToolRun);

        executor.onToolRunFinished(2001L);

        // 第二步是同步工具 → 直接内联执行
        verify(toolRunService).createToolRun(any(), any(),
                org.mockito.ArgumentMatchers.eq(ToolRunStatusEnum.PENDING.getValue()));
        verify(toolRunService).executeInline(anyLong());
        assertEquals(AgentRunStatusEnum.RUNNING.getValue(), run.getStatus());
    }

    @Test
    void allStepsSucceededSettlesRun() {
        PlanStep only = step("s1", "replace_background");
        only.setToolRunId(2001L);
        only.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        PictureAgentRun run = run(Collections.singletonList(only));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        PictureToolRun okToolRun = new PictureToolRun();
        okToolRun.setId(2001L);
        okToolRun.setAgentRunId(1001L);
        okToolRun.setStatus(ToolRunStatusEnum.SUCCEEDED.getValue());
        when(toolRunMapper.selectById(2001L)).thenReturn(okToolRun);

        executor.onToolRunFinished(2001L);
        assertEquals(AgentRunStatusEnum.SUCCEEDED.getValue(), run.getStatus());
        assertNull(run.getErrorMessage());
    }

    @Test
    void cancelOnlyTouchesCancelableStepsAndMarksIntentForRunning() {
        PlanStep pendingStep = step("s2", "flip_layer", "s1");
        pendingStep.setToolRunId(2002L);
        pendingStep.setStatus(ToolRunStatusEnum.QUEUED.getValue());
        PlanStep runningStep = step("s1", "replace_background");
        runningStep.setToolRunId(2001L);
        runningStep.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        PictureAgentRun run = run(Arrays.asList(runningStep, pendingStep));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        PictureToolRun running = new PictureToolRun();
        running.setId(2001L);
        running.setStatus(ToolRunStatusEnum.RUNNING.getValue());
        PictureToolRun queued = new PictureToolRun();
        queued.setId(2002L);
        queued.setStatus(ToolRunStatusEnum.QUEUED.getValue());
        when(toolRunMapper.selectById(2001L)).thenReturn(running);
        when(toolRunMapper.selectById(2002L)).thenReturn(queued);

        executor.cancel(1001L, user);

        assertEquals(AgentRunStatusEnum.CANCELED.getValue(), run.getStatus());
        verify(toolRunService).markCancelIntent(2001L);
        verify(toolRunMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    void retryResetsNonSucceededStepsAndRedispatches() {
        PlanStep okStep = step("s1", "replace_background");
        okStep.setToolRunId(1L);
        okStep.setStatus(ToolRunStatusEnum.SUCCEEDED.getValue());
        PlanStep failedStep = step("s2", "flip_layer", "s1");
        failedStep.setToolRunId(2L);
        failedStep.setStatus(ToolRunStatusEnum.FAILED.getValue());
        PictureAgentRun run = run(Arrays.asList(okStep, failedStep));
        run.setStatus(AgentRunStatusEnum.FAILED.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);

        executor.retry(1001L, user);

        assertEquals(AgentRunStatusEnum.RUNNING.getValue(), run.getStatus());
        List<PlanStep> steps = executor.parseSteps(run);
        // 成功步骤保留；失败步骤被重置并重新派发（分配了新的 toolRunId，且不等于旧任务 2L）
        assertEquals(ToolRunStatusEnum.SUCCEEDED.getValue(), steps.get(0).getStatus());
        assertNotNull(steps.get(1).getToolRunId());
        assertTrue(steps.get(1).getToolRunId() != 2L);
        // s2 依赖已成功 → 立即派发（同步工具内联执行）
        verify(toolRunService).createToolRun(any(), any(), anyString());
        verify(toolRunService).executeInline(anyLong());
    }

    @Test
    void retryRejectedForNonTerminalRun() {
        PictureAgentRun run = run(Collections.singletonList(step("s1", "flip_layer")));
        run.setStatus(AgentRunStatusEnum.RUNNING.getValue());
        when(agentRunMapper.selectById(1001L)).thenReturn(run);
        assertThrows(BusinessException.class, () -> executor.retry(1001L, user));
    }

    @Test
    void parseStepsHandlesBlankPlan() {
        PictureAgentRun run = new PictureAgentRun();
        run.setId(1L);
        assertTrue(executor.parseSteps(run).isEmpty());
        assertNotNull(executor.parseSteps(run));
    }
}

package com.zys.backend.agent;

import com.zys.backend.agent.service.AgentEventPublisher;
import com.zys.backend.agent.service.PlanExecutor;
import com.zys.backend.agent.service.ToolRunService;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolHandler;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.mapper.PictureToolRunMapper;
import com.zys.backend.model.entity.PictureToolRun;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import com.zys.backend.agent.support.AgentTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis Streams 消费幂等：终态任务重复消费直接跳过，不再执行 handler / 更新状态 / 发事件
 */
class ToolRunServiceTest {

    private ToolRunService toolRunService;
    private PictureToolRunMapper toolRunMapper;
    private AgentEventPublisher eventPublisher;
    private PlanExecutor planExecutor;
    private final AtomicInteger handlerCalls = new AtomicInteger();

    @BeforeAll
    static void initEntities() {
        AgentTestSupport.initTableInfo(PictureToolRun.class);
    }

    @BeforeEach
    void setUp() {
        AgentToolHandler countingHandler = (ctx, params) -> {
            handlerCalls.incrementAndGet();
            return new HashMap<>();
        };
        ToolSpec spec = new ToolSpec()
                .setName("queued_tool")
                .setLabel("异步工具")
                .setQueued(true)
                .setHandler(countingHandler);
        ToolRegistry registry = new ToolRegistry();
        AgentTool tool = () -> Collections.singletonList(spec);
        ReflectionTestUtils.setField(registry, "tools", Collections.singletonList(tool));
        registry.init();

        toolRunService = new ToolRunService();
        toolRunMapper = mock(PictureToolRunMapper.class);
        eventPublisher = mock(AgentEventPublisher.class);
        planExecutor = mock(PlanExecutor.class);
        ReflectionTestUtils.setField(toolRunService, "toolRunMapper", toolRunMapper);
        ReflectionTestUtils.setField(toolRunService, "toolRegistry", registry);
        ReflectionTestUtils.setField(toolRunService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(toolRunService, "planExecutor", planExecutor);
    }

    private PictureToolRun run(String status) {
        PictureToolRun run = new PictureToolRun();
        run.setId(2001L);
        run.setAgentRunId(1001L);
        run.setTool("queued_tool");
        run.setStatus(status);
        run.setProgress(100);
        return run;
    }

    @Test
    void terminalRunsAreSkippedOnDuplicateConsumption() {
        for (String terminal : Arrays.asList(
                ToolRunStatusEnum.SUCCEEDED.getValue(),
                ToolRunStatusEnum.FAILED.getValue(),
                ToolRunStatusEnum.CANCELED.getValue())) {
            when(toolRunMapper.selectById(2001L)).thenReturn(run(terminal));
            toolRunService.executeQueued(2001L);
        }
        assertEquals(0, handlerCalls.get());
        verify(toolRunMapper, never()).update(any(), any());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void missingRunIsIgnored() {
        when(toolRunMapper.selectById(404L)).thenReturn(null);
        toolRunService.executeQueued(404L);
        assertEquals(0, handlerCalls.get());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void concurrentClaimOnlyRunsOnce() {
        when(toolRunMapper.selectById(2001L)).thenReturn(run(ToolRunStatusEnum.QUEUED.getValue()));
        // 乐观抢占失败（另一个消费者已置为 running）
        when(toolRunMapper.update(any(), any())).thenReturn(0);
        toolRunService.executeQueued(2001L);
        assertEquals(0, handlerCalls.get());
        verify(planExecutor, never()).onToolRunFinished(any());
    }

    @Test
    void helpersReportTerminalState() {
        assertFalse(ToolRunStatusEnum.isTerminal(ToolRunStatusEnum.QUEUED.getValue()));
        assertEquals(true, ToolRunStatusEnum.isTerminal(ToolRunStatusEnum.SUCCEEDED.getValue()));
        assertEquals(true, ToolRunStatusEnum.isCancelable(ToolRunStatusEnum.WAITING.getValue()));
        assertEquals(false, ToolRunStatusEnum.isCancelable(ToolRunStatusEnum.RUNNING.getValue()));
    }
}

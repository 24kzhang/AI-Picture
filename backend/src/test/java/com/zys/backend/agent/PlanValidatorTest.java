package com.zys.backend.agent;

import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.service.PlanValidator;
import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolHandler;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanValidator：最多 8 步、默认依赖、依赖缺失、环检测、多步确认
 */
class PlanValidatorTest {

    private PlanValidator validator;

    private static final AgentToolHandler NOOP = (ctx, params) -> new HashMap<>();

    @BeforeEach
    void setUp() {
        ToolSpec syncTool = new ToolSpec()
                .setName("flip_layer")
                .setLabel("翻转")
                .setQueued(false)
                .setSessionRequired(true)
                .setHandler(NOOP);
        ToolSpec asyncTool = new ToolSpec()
                .setName("replace_background")
                .setLabel("换背景")
                .setQueued(true)
                .setSessionRequired(true)
                .setHandler(NOOP);
        ToolSpec freeTool = new ToolSpec()
                .setName("batch_process")
                .setLabel("批量")
                .setQueued(true)
                .setSessionRequired(false)
                .setHandler(NOOP);
        ToolRegistry registry = new ToolRegistry();
        AgentTool tool = () -> Arrays.asList(syncTool, asyncTool, freeTool);
        ReflectionTestUtils.setField(registry, "tools", Collections.singletonList(tool));
        registry.init();
        validator = new PlanValidator();
        ReflectionTestUtils.setField(validator, "toolRegistry", registry);
    }

    private Map<String, Object> step(String tool, Map<String, Object> params) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tool", tool);
        raw.put("params", params == null ? new HashMap<String, Object>() : params);
        return raw;
    }

    private PictureEditSession session() {
        PictureEditSession session = new PictureEditSession();
        session.setId(1L);
        session.setRevision(1);
        return session;
    }

    @Test
    void shouldFillStepIdsAndDefaultDependencies() {
        List<Map<String, Object>> raw = Arrays.asList(
                step("replace_background", null),
                step("flip_layer", null));
        List<PlanStep> steps = validator.validate(raw, session());
        assertEquals(2, steps.size());
        assertEquals("s1", steps.get(0).getId());
        assertEquals("s2", steps.get(1).getId());
        assertTrue(steps.get(0).getDependsOn().isEmpty());
        assertEquals(Collections.singletonList("s1"), steps.get(1).getDependsOn());
        assertEquals(ToolRunStatusEnum.PENDING.getValue(), steps.get(0).getStatus());
    }

    @Test
    void shouldRejectMoreThanEightSteps() {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            raw.add(step("flip_layer", null));
        }
        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validate(raw, session()));
        assertTrue(error.getMessage().contains("8"));
    }

    @Test
    void shouldRejectMissingDependency() {
        Map<String, Object> raw = step("flip_layer", null);
        raw.put("dependsOn", Collections.singletonList("sX"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validate(Collections.singletonList(raw), session()));
        assertTrue(error.getMessage().contains("依赖了不存在的步骤"));
    }

    @Test
    void shouldRejectCyclicDependency() {
        Map<String, Object> first = step("flip_layer", null);
        first.put("id", "a");
        first.put("dependsOn", Collections.singletonList("b"));
        Map<String, Object> second = step("flip_layer", null);
        second.put("id", "b");
        second.put("dependsOn", Collections.singletonList("a"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> validator.validate(Arrays.asList(first, second), session()));
        assertTrue(error.getMessage().contains("循环依赖"));
    }

    @Test
    void shouldRejectUnknownTool() {
        assertThrows(BusinessException.class,
                () -> validator.validate(Collections.singletonList(step("unknown_tool", null)), session()));
    }

    @Test
    void shouldEnforceSessionRequirement() {
        assertThrows(BusinessException.class,
                () -> validator.validate(Collections.singletonList(step("flip_layer", null)), null));
        List<PlanStep> steps = validator.validate(
                Collections.singletonList(step("batch_process", null)), null);
        assertEquals(1, steps.size());
    }

    @Test
    void needsConfirmOnlyForMultiStep() {
        assertFalse(validator.needsConfirm(Collections.singletonList(new PlanStep())));
        assertTrue(validator.needsConfirm(Arrays.asList(new PlanStep(), new PlanStep())));
        assertFalse(validator.needsConfirm(Collections.emptyList()));
    }

    @Test
    void readyStepsRespectDependenciesAndTerminalStates() {
        PlanStep first = new PlanStep();
        first.setId("s1");
        first.setTool("flip_layer");
        first.setStatus(ToolRunStatusEnum.SUCCEEDED.getValue());
        PlanStep second = new PlanStep();
        second.setId("s2");
        second.setTool("flip_layer");
        second.setDependsOn(new ArrayList<>(Collections.singletonList("s1")));
        second.setStatus(ToolRunStatusEnum.PENDING.getValue());
        List<PlanStep> ready = validator.ready(Arrays.asList(first, second));
        assertEquals(1, ready.size());
        assertEquals("s2", ready.get(0).getId());

        // 已创建任务的步骤不再进入 ready
        second.setToolRunId(99L);
        assertTrue(validator.ready(Arrays.asList(first, second)).isEmpty());

        // 依赖失败 → 被阻断
        second.setToolRunId(null);
        first.setStatus(ToolRunStatusEnum.FAILED.getValue());
        assertTrue(validator.ready(Arrays.asList(first, second)).isEmpty());
        Map<String, PlanStep> byId = new HashMap<>();
        byId.put("s1", first);
        byId.put("s2", second);
        assertTrue(validator.isBlocked(second, byId));
    }
}

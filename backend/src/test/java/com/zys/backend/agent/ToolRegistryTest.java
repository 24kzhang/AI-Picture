package com.zys.backend.agent;

import com.zys.backend.agent.tool.AgentTool;
import com.zys.backend.agent.tool.AgentToolHandler;
import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.exception.BusinessException;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry：注册、参数校验、未知工具拒绝、模型签名生成
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    private static final AgentToolHandler NOOP_HANDLER = (ctx, params) -> new HashMap<>();

    @BeforeEach
    void setUp() {
        ToolSpec simple = new ToolSpec()
                .setName("demo_tool")
                .setLabel("示例")
                .setDescription("演示工具")
                .setQueued(false)
                .setSessionRequired(true)
                .setHandler(NOOP_HANDLER)
                .setParams(Arrays.asList(
                        ParamField.string("prompt").setRequired(true).setMaxLength(500),
                        ParamField.integer("count").setDefaultValue(1).setMin(1.0).setMax(4.0),
                        ParamField.string("ratio").setChoices(Arrays.<Object>asList("1:1", "4:5")),
                        ParamField.string("internal").setAgentHidden(true)));

        ToolSpec cross = new ToolSpec()
                .setName("cross_tool")
                .setLabel("跨字段")
                .setDescription("跨字段校验工具")
                .setHandler(NOOP_HANDLER)
                .setParams(Arrays.asList(
                        ParamField.number("a"),
                        ParamField.number("b")))
                .setCrossValidator(params ->
                        params.get("a") == null && params.get("b") == null ? "a 或 b 至少一个" : null);

        AgentTool tool = () -> Arrays.asList(simple, cross);
        registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "tools", Collections.singletonList(tool));
        registry.init();
    }

    @Test
    void shouldRegisterAllTools() {
        assertEquals(2, registry.size());
        assertTrue(registry.contains("demo_tool"));
        assertNotNull(registry.specOf("cross_tool"));
        List<ToolSpec> all = registry.all();
        assertEquals("demo_tool", all.get(0).getName());
    }

    @Test
    void shouldRejectUnknownTool() {
        assertFalse(registry.contains("not_exists"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> registry.specOf("not_exists"));
        assertTrue(error.getMessage().contains("未注册的工具"));
    }

    @Test
    void shouldRejectDuplicateRegistration() {
        AgentTool duplicated = () -> Collections.singletonList(new ToolSpec()
                .setName("demo_tool")
                .setLabel("重复")
                .setHandler(NOOP_HANDLER));
        AgentTool original = () -> Collections.singletonList(new ToolSpec()
                .setName("demo_tool")
                .setLabel("原始")
                .setHandler(NOOP_HANDLER));
        ToolRegistry second = new ToolRegistry();
        ReflectionTestUtils.setField(second, "tools", Arrays.asList(original, duplicated));
        assertThrows(IllegalStateException.class, second::init);
    }

    @Test
    void shouldValidateParamsWithDefaultsAndBounds() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("prompt", "把背景换成白色");
        Map<String, Object> normalized = registry.validateParams("demo_tool", raw);
        assertEquals("把背景换成白色", normalized.get("prompt"));
        assertEquals(1, normalized.get("count"));
        assertFalse(normalized.containsKey("ratio"));
    }

    @Test
    void shouldRejectInvalidParams() {
        Map<String, Object> missingRequired = new HashMap<>();
        BusinessException missing = assertThrows(BusinessException.class,
                () -> registry.validateParams("demo_tool", missingRequired));
        assertTrue(missing.getMessage().contains("prompt"));

        Map<String, Object> badChoice = new LinkedHashMap<>();
        badChoice.put("prompt", "x");
        badChoice.put("ratio", "16:9");
        assertThrows(BusinessException.class, () -> registry.validateParams("demo_tool", badChoice));

        Map<String, Object> outOfRange = new LinkedHashMap<>();
        outOfRange.put("prompt", "x");
        outOfRange.put("count", 9);
        assertThrows(BusinessException.class, () -> registry.validateParams("demo_tool", outOfRange));

        Map<String, Object> tooLong = new LinkedHashMap<>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            longText.append('x');
        }
        tooLong.put("prompt", longText.toString());
        assertThrows(BusinessException.class, () -> registry.validateParams("demo_tool", tooLong));
    }

    @Test
    void shouldApplyCrossFieldValidation() {
        Map<String, Object> empty = new HashMap<>();
        assertThrows(BusinessException.class, () -> registry.validateParams("cross_tool", empty));
        Map<String, Object> withA = new HashMap<>();
        withA.put("a", 0.5);
        assertEquals(0.5, (Double) registry.validateParams("cross_tool", withA).get("a"), 0.0001);
    }

    @Test
    void shouldHideAgentHiddenFieldsFromModelSchema() {
        JSONArray schemas = registry.toAgentToolSchemas();
        JSONObject demo = null;
        for (Object item : schemas) {
            JSONObject wrapper = (JSONObject) item;
            if ("demo_tool".equals(wrapper.getJSONObject("function").getStr("name"))) {
                demo = wrapper.getJSONObject("function");
            }
        }
        assertNotNull(demo);
        JSONObject parameters = demo.getJSONObject("parameters");
        assertTrue(parameters.getJSONObject("properties").containsKey("prompt"));
        assertFalse(parameters.getJSONObject("properties").containsKey("internal"));
        assertEquals(Collections.singletonList("prompt"), parameters.getJSONArray("required").toList(String.class));
        JSONObject countSchema = parameters.getJSONObject("properties").getJSONObject("count");
        assertEquals(1.0, countSchema.getDouble("minimum"), 0.0001);
        assertEquals(4.0, countSchema.getDouble("maximum"), 0.0001);
    }
}

package com.zys.backend.agent.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 版工具注册表，统一给前端、Planner、Validator、Executor 使用。
 * 新增工具只需实现 {@link AgentTool} 并登记 ToolSpec。
 */
@Slf4j
@Component
public class ToolRegistry {

    @Resource
    private List<AgentTool> tools;

    private final Map<String, ToolSpec> specByName = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (AgentTool tool : tools) {
            for (ToolSpec spec : tool.specs()) {
                if (spec.getName() == null || spec.getName().isEmpty()) {
                    throw new IllegalStateException("工具名不能为空：" + tool.getClass().getName());
                }
                if (spec.getHandler() == null) {
                    throw new IllegalStateException("工具缺少 handler：" + spec.getName());
                }
                ToolSpec previous = specByName.put(spec.getName(), spec);
                if (previous != null) {
                    throw new IllegalStateException("工具重复注册：" + spec.getName());
                }
            }
        }
        log.info("Agent 工具注册完成，共 {} 个：{}", specByName.size(), specByName.keySet());
    }

    /**
     * 按名称取工具定义，未注册直接拒绝
     */
    public ToolSpec specOf(String name) {
        ToolSpec spec = specByName.get(name);
        if (spec == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "未注册的工具：" + name);
        }
        return spec;
    }

    public boolean contains(String name) {
        return specByName.containsKey(name);
    }

    public List<ToolSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(specByName.values()));
    }

    public int size() {
        return specByName.size();
    }

    /**
     * 校验并归一化参数，界面与 Agent 走同一套规则
     */
    public Map<String, Object> validateParams(String toolName, Map<String, Object> raw) {
        return ParamValidator.validate(specOf(toolName), raw);
    }

    /**
     * 生成给规划模型的 OpenAI function-calling 工具签名（隐藏服务端注入参数）
     */
    public JSONArray toAgentToolSchemas() {
        JSONArray array = new JSONArray();
        for (ToolSpec spec : specByName.values()) {
            JSONObject properties = new JSONObject();
            JSONArray required = new JSONArray();
            for (ParamField field : spec.getParams()) {
                if (field.isAgentHidden()) {
                    continue;
                }
                properties.set(field.getName(), toSchema(field));
                if (field.isRequired()) {
                    required.add(field.getName());
                }
            }
            JSONObject parameters = new JSONObject()
                    .set("type", "object")
                    .set("properties", properties)
                    .set("required", required);
            JSONObject function = new JSONObject()
                    .set("name", spec.getName())
                    .set("description", spec.getDescription())
                    .set("parameters", parameters);
            array.add(new JSONObject().set("type", "function").set("function", function));
        }
        return array;
    }

    private JSONObject toSchema(ParamField field) {
        JSONObject schema = new JSONObject();
        switch (field.getType()) {
            case ParamField.TYPE_ARRAY:
                schema.set("type", "array");
                JSONObject items = new JSONObject()
                        .set("type", field.getItemType() == null ? "string" : field.getItemType());
                if (field.getItemChoices() != null) {
                    items.set("enum", field.getItemChoices());
                }
                schema.set("items", items);
                break;
            default:
                schema.set("type", field.getType());
                if (field.getChoices() != null) {
                    schema.set("enum", field.getChoices());
                }
                if (field.getMin() != null) {
                    schema.set("minimum", field.getMin());
                }
                if (field.getMax() != null) {
                    schema.set("maximum", field.getMax());
                }
                break;
        }
        if (field.getDefaultValue() != null) {
            schema.set("default", field.getDefaultValue());
        }
        if (field.getDescription() != null) {
            schema.set("description", field.getDescription());
        }
        return schema;
    }
}

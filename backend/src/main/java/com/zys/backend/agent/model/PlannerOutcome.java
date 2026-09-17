package com.zys.backend.agent.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划模型一轮输出：文本回复 + 原始工具调用。
 */
@Data
public class PlannerOutcome {

    /**
     * 模型文本回复
     */
    private String reply;

    /**
     * 原始工具调用：tool + arguments
     */
    private List<Map<String, Object>> rawSteps = new ArrayList<>();

    public PlannerOutcome addStep(String tool, Map<String, Object> arguments) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("params", arguments == null ? new LinkedHashMap<String, Object>() : arguments);
        rawSteps.add(step);
        return this;
    }
}

package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.agent.model.PlanStep;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolSpec;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.ToolRunStatusEnum;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划校验器：工具存在、参数合法、最多 8 步、依赖存在、无环、
 * 会话约束（sessionRequired/revision）；补齐 step id、默认依赖、初始状态。
 */
@Component
public class PlanValidator {

    @Resource
    private ToolRegistry toolRegistry;

    /**
     * 校验原始步骤列表并产出规范化计划
     */
    public List<PlanStep> validate(List<Map<String, Object>> rawSteps, PictureEditSession session) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return new ArrayList<>();
        }
        if (rawSteps.size() > AgentConstant.MAX_PLAN_STEPS) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "计划超过 " + AgentConstant.MAX_PLAN_STEPS + " 步，请拆成多次说明");
        }
        List<PlanStep> steps = new ArrayList<>();
        for (Map<String, Object> raw : rawSteps) {
            steps.add(toStep(raw, session));
        }
        assemble(steps);
        return steps;
    }

    @SuppressWarnings("unchecked")
    private PlanStep toStep(Map<String, Object> raw, PictureEditSession session) {
        Object toolValue = raw.get("tool");
        if (toolValue == null || StrUtil.isBlank(String.valueOf(toolValue))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划步骤缺少工具名");
        }
        String toolName = String.valueOf(toolValue);
        ToolSpec spec = toolRegistry.specOf(toolName);
        if (spec.isSessionRequired() && session == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "工具 " + toolName + " 需要在编辑会话中使用");
        }
        Map<String, Object> rawParams = raw.get("params") instanceof Map
                ? (Map<String, Object>) raw.get("params") : new LinkedHashMap<>();
        Map<String, Object> params = toolRegistry.validateParams(toolName, rawParams);

        PlanStep step = new PlanStep();
        step.setTool(spec.getName());
        step.setParams(params);
        step.setNeedsApproval(spec.isNeedsApproval());
        Object idValue = raw.get("id");
        step.setId(idValue == null ? null : String.valueOf(idValue));
        Object dependsValue = raw.get("dependsOn") != null ? raw.get("dependsOn") : raw.get("depends_on");
        if (dependsValue instanceof List) {
            List<String> depends = new ArrayList<>();
            for (Object item : (List<Object>) dependsValue) {
                if (item != null) {
                    depends.add(String.valueOf(item));
                }
            }
            step.setDependsOn(depends);
        } else if (dependsValue == null) {
            step.setDependsOn(null);
        }
        Object statusValue = raw.get("status");
        step.setStatus(statusValue == null ? ToolRunStatusEnum.PENDING.getValue() : String.valueOf(statusValue));
        return step;
    }

    /**
     * 补全步骤 id、顺序依赖并拒绝依赖缺失或成环的计划
     */
    private void assemble(List<PlanStep> steps) {
        for (int index = 0; index < steps.size(); index++) {
            PlanStep step = steps.get(index);
            if (StrUtil.isBlank(step.getId())) {
                step.setId("s" + (index + 1));
            }
            if (step.getDependsOn() == null) {
                List<String> depends = new ArrayList<>();
                if (index > 0) {
                    depends.add(steps.get(index - 1).getId());
                }
                step.setDependsOn(depends);
            }
        }
        Set<String> known = new HashSet<>();
        for (PlanStep step : steps) {
            if (!known.add(step.getId())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划步骤 id 重复：" + step.getId());
            }
        }
        for (PlanStep step : steps) {
            for (String dep : step.getDependsOn()) {
                if (!known.contains(dep)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "步骤 " + step.getId() + " 依赖了不存在的步骤 " + dep);
                }
            }
        }
        if (cyclic(steps)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划步骤存在循环依赖");
        }
    }

    /**
     * Kahn 拓扑排序检测环
     */
    private boolean cyclic(List<PlanStep> steps) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (PlanStep step : steps) {
            incoming.put(step.getId(), 0);
            outgoing.put(step.getId(), new ArrayList<>());
        }
        for (PlanStep step : steps) {
            for (String dep : step.getDependsOn()) {
                outgoing.get(dep).add(step.getId());
                incoming.put(step.getId(), incoming.get(step.getId()) + 1);
            }
        }
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : incoming.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int seen = 0;
        while (!queue.isEmpty()) {
            String node = queue.remove(queue.size() - 1);
            seen++;
            for (String next : outgoing.get(node)) {
                int count = incoming.get(next) - 1;
                incoming.put(next, count);
                if (count == 0) {
                    queue.add(next);
                }
            }
        }
        return seen != steps.size();
    }

    /**
     * 多步计划需要用户确认；单步直接执行
     */
    public boolean needsConfirm(List<PlanStep> steps) {
        return steps != null && steps.size() > 1;
    }

    /**
     * 找出依赖全部成功且自身待执行的步骤
     */
    public List<PlanStep> ready(List<PlanStep> steps) {
        Map<String, PlanStep> byId = new HashMap<>();
        for (PlanStep step : steps) {
            byId.put(step.getId(), step);
        }
        List<PlanStep> result = new ArrayList<>();
        for (PlanStep step : steps) {
            if (isReady(step, byId)) {
                result.add(step);
            }
        }
        return result;
    }

    private boolean isReady(PlanStep step, Map<String, PlanStep> byId) {
        String status = step.getStatus();
        boolean open = ToolRunStatusEnum.PENDING.getValue().equals(status)
                || ToolRunStatusEnum.WAITING.getValue().equals(status);
        if (!open || step.getToolRunId() != null) {
            return false;
        }
        for (String dep : step.getDependsOn()) {
            PlanStep depStep = byId.get(dep);
            if (depStep == null) {
                return false;
            }
            if (!ToolRunStatusEnum.SUCCEEDED.getValue().equals(depStep.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 依赖被阻断（失败/取消）的步骤保持等待，不再推进
     */
    public boolean isBlocked(PlanStep step, Map<String, PlanStep> byId) {
        for (String dep : step.getDependsOn()) {
            PlanStep depStep = byId.get(dep);
            if (depStep != null && (ToolRunStatusEnum.FAILED.getValue().equals(depStep.getStatus())
                    || ToolRunStatusEnum.CANCELED.getValue().equals(depStep.getStatus()))) {
                return true;
            }
        }
        return false;
    }
}

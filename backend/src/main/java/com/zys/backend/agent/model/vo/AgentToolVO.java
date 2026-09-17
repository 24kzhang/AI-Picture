package com.zys.backend.agent.model.vo;

import com.zys.backend.agent.tool.ParamField;
import com.zys.backend.agent.tool.ToolSpec;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具信息（前端工具面板使用）
 */
@Data
public class AgentToolVO {

    private String name;

    private String label;

    private String description;

    private boolean queued;

    private boolean needsApproval;

    private boolean sessionRequired;

    /**
     * 对模型隐藏的参数字段
     */
    private List<ParamField> params;

    public static AgentToolVO from(ToolSpec spec) {
        AgentToolVO vo = new AgentToolVO();
        vo.setName(spec.getName());
        vo.setLabel(spec.getLabel());
        vo.setDescription(spec.getDescription());
        vo.setQueued(spec.isQueued());
        vo.setNeedsApproval(spec.isNeedsApproval());
        vo.setSessionRequired(spec.isSessionRequired());
        vo.setParams(spec.getParams().stream().collect(Collectors.toList()));
        return vo;
    }
}

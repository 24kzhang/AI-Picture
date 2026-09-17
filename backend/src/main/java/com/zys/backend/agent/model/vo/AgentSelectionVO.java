package com.zys.backend.agent.model.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 选区信息
 */
@Data
public class AgentSelectionVO {

    private Long sessionId;

    /**
     * 选区绑定的画布 revision，画布变更后自动失效
     */
    private Integer revision;

    /**
     * mask 资产 id
     */
    private Long maskAssetId;

    /**
     * mask 资产 URL
     */
    private String maskUrl;

    /**
     * 前端标记（矩形/笔刷轨迹），用于回显
     */
    private List<Map<String, Object>> markers;
}

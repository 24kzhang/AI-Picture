package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 保存选区请求
 */
@Data
public class AgentSelectionRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * prepare 阶段产出的 mask 资产 id
     */
    private Long maskAssetId;

    /**
     * 前端标记（矩形/笔刷轨迹），用于回显
     */
    private List<Map<String, Object>> markers;

    /**
     * 客户端当前画布 revision，防旧页面写回过期选区
     */
    private Integer revision;
}

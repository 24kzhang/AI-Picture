package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 直接调用工具请求（不经过规划模型）
 */
@Data
public class AgentToolRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 工具参数
     */
    private Map<String, Object> params;
}

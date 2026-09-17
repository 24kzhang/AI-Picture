package com.zys.backend.agent.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 发送自然语言指令请求
 */
@Data
public class AgentMessageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户自然语言指令
     */
    private String message;

    /**
     * 附加上下文（如当前选中的图层 id）
     */
    private Map<String, Object> context;
}

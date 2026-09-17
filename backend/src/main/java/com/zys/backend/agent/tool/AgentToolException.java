package com.zys.backend.agent.tool;

/**
 * 可直接展示给用户的工具执行失败。
 */
public class AgentToolException extends RuntimeException {

    public AgentToolException(String message) {
        super(message);
    }

    public AgentToolException(String message, Throwable cause) {
        super(message, cause);
    }
}

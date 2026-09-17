package com.zys.backend.constant;

/**
 * Java 原生修图 Agent 相关常量
 */
public interface AgentConstant {

    /**
     * 编辑租约 Redis key 前缀，后接 pictureId
     */
    String EDIT_LOCK_KEY_PREFIX = "gallery:picture:edit-lock:";

    /**
     * 编辑租约 TTL（秒），前端每 20 秒续租
     */
    long EDIT_LOCK_TTL_SECONDS = 60;

    /**
     * 编辑租约模式：快捷编辑
     */
    String EDIT_LOCK_MODE_QUICK = "QUICK";

    /**
     * 编辑租约模式：Agent 精修
     */
    String EDIT_LOCK_MODE_AGENT = "AGENT";

    /**
     * 选区缓存 Redis key 前缀，后接 sessionId
     */
    String SELECTION_KEY_PREFIX = "gallery:agent:selection:";

    /**
     * 选区缓存 TTL（小时）
     */
    long SELECTION_TTL_HOURS = 24;

    /**
     * 工具队列 Redis Stream key
     */
    String TOOL_RUN_STREAM_KEY = "gallery:agent:tool-run-stream";

    /**
     * 工具队列消费者组
     */
    String TOOL_RUN_CONSUMER_GROUP = "gallery-agent-workers";

    /**
     * 实时事件 Pub/Sub 频道前缀，后接 runId
     */
    String RUN_EVENT_CHANNEL_PREFIX = "gallery:agent:run:";

    /**
     * 单个计划最大步骤数
     */
    int MAX_PLAN_STEPS = 8;

    /**
     * 工具任务最大重试次数
     */
    int MAX_TOOL_RUN_RETRIES = 3;

    /**
     * Stream 消息中携带 toolRunId 的字段名
     */
    String STREAM_FIELD_TOOL_RUN_ID = "toolRunId";
}

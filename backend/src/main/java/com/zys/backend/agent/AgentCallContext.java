package com.zys.backend.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 修图 Agent 调用上下文：携带云图库用户、空间、图片与权限信息，
 * 用于签发服务令牌并在 Agent 侧建立影子会话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCallContext {

    /**
     * 云图库用户 id（必填）
     */
    private Long userId;

    /**
     * 云图库图片 id
     */
    private Long pictureId;

    /**
     * 云图库空间 id；公共图库为 null
     */
    private Long spaceId;

    /**
     * 用户在目标空间上的权限列表
     */
    private List<String> permissions;

    /**
     * 请求 ID（用于日志追踪）
     */
    private String requestId;
}

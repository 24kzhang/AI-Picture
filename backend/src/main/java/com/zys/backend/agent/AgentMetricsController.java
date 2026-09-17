package com.zys.backend.agent;

import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.annotation.AuthCheck;
import com.zys.backend.constant.UserConstant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 融合指标（管理员）：请求成功率、运行状态计数、提交冲突、批量逐项、
 * 临时清理、SSE 连接、导出次数与 Outbox 积压。
 */
@RestController
public class AgentMetricsController {

    @Resource
    private AgentMetrics agentMetrics;

    @Resource
    private RetouchAgentClient agentClient;

    @GetMapping("/agent-metrics")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String, Object>> metrics() {
        Map<String, Object> snapshot = new LinkedHashMap<>(agentMetrics.snapshot());
        snapshot.put("agentEnabled", agentClient.isEnabled());
        snapshot.put("agentAvailable", agentClient.isAvailable());
        return ResultUtils.success(snapshot);
    }
}

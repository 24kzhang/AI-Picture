package com.zys.backend.agent;

import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.enums.OutboxStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent 融合轻量指标：进程内计数 + Outbox 积压实时查询。
 * 通过 GET /api/agent-metrics（管理员）查看。
 */
@Component
public class AgentMetrics {

    private final LongAdder agentRequestSuccess = new LongAdder();
    private final LongAdder agentRequestFailure = new LongAdder();
    private final LongAdder runSucceeded = new LongAdder();
    private final LongAdder runFailed = new LongAdder();
    private final LongAdder runCanceled = new LongAdder();
    private final LongAdder commitConflict = new LongAdder();
    private final LongAdder batchItemsSucceeded = new LongAdder();
    private final LongAdder batchItemsFailed = new LongAdder();
    private final LongAdder cleanupDeleted = new LongAdder();
    private final LongAdder cleanupFailure = new LongAdder();
    private final LongAdder sseConnections = new LongAdder();
    private final LongAdder exportCount = new LongAdder();

    @Resource
    private IntegrationOutboxMapper outboxMapper;

    public void incrementAgentRequestSuccess() {
        agentRequestSuccess.increment();
    }

    public void incrementAgentRequestFailure() {
        agentRequestFailure.increment();
    }

    public void recordRunStatus(String status) {
        if ("succeeded".equals(status)) {
            runSucceeded.increment();
        } else if ("failed".equals(status)) {
            runFailed.increment();
        } else if ("canceled".equals(status)) {
            runCanceled.increment();
        }
    }

    public void incrementCommitConflict() {
        commitConflict.increment();
    }

    public void recordBatchItem(String status) {
        if ("succeeded".equals(status)) {
            batchItemsSucceeded.increment();
        } else if ("failed".equals(status) || "conflict".equals(status)) {
            batchItemsFailed.increment();
        }
    }

    public void addCleanupDeleted(int count) {
        cleanupDeleted.add(count);
    }

    public void incrementCleanupFailure() {
        cleanupFailure.increment();
    }

    public void incrementSseConnection() {
        sseConnections.increment();
    }

    public void incrementExport() {
        exportCount.increment();
    }

    /**
     * 指标快照（含 Outbox 积压）
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("agentRequestSuccess", agentRequestSuccess.sum());
        snapshot.put("agentRequestFailure", agentRequestFailure.sum());
        snapshot.put("runSucceeded", runSucceeded.sum());
        snapshot.put("runFailed", runFailed.sum());
        snapshot.put("runCanceled", runCanceled.sum());
        snapshot.put("commitConflict", commitConflict.sum());
        snapshot.put("batchItemsSucceeded", batchItemsSucceeded.sum());
        snapshot.put("batchItemsFailed", batchItemsFailed.sum());
        snapshot.put("cleanupDeleted", cleanupDeleted.sum());
        snapshot.put("cleanupFailure", cleanupFailure.sum());
        snapshot.put("sseConnections", sseConnections.sum());
        snapshot.put("exportCount", exportCount.sum());
        snapshot.put("outboxPending", countOutbox(OutboxStatusEnum.PENDING));
        snapshot.put("outboxProcessing", countOutbox(OutboxStatusEnum.PROCESSING));
        snapshot.put("outboxFailed", countOutbox(OutboxStatusEnum.FAILED));
        return snapshot;
    }

    private long countOutbox(OutboxStatusEnum status) {
        try {
            Long count = outboxMapper.selectCount(new QueryWrapper<IntegrationOutbox>()
                    .eq("status", status.getValue()));
            return count == null ? 0L : count;
        } catch (Exception e) {
            return -1L;
        }
    }
}

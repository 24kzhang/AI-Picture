package com.zys.backend.agent;

import com.zys.backend.mapper.IntegrationOutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentMetricsTest {

    @Mock
    private IntegrationOutboxMapper outboxMapper;

    @InjectMocks
    private AgentMetrics metrics;

    @Test
    void countsRunsBatchesAndOutbox() {
        when(outboxMapper.selectCount(any())).thenReturn(3L);

        metrics.recordRunStatus("succeeded");
        metrics.recordRunStatus("succeeded");
        metrics.recordRunStatus("failed");
        metrics.recordRunStatus("canceled");
        metrics.recordBatchItem("succeeded");
        metrics.recordBatchItem("conflict");
        metrics.recordBatchItem("failed");
        metrics.incrementAgentRequestSuccess();
        metrics.incrementAgentRequestFailure();
        metrics.incrementCommitConflict();
        metrics.incrementSseConnection();
        metrics.incrementExport();

        Map<String, Object> snapshot = metrics.snapshot();

        assertEquals(2L, snapshot.get("runSucceeded"));
        assertEquals(1L, snapshot.get("runFailed"));
        assertEquals(1L, snapshot.get("runCanceled"));
        assertEquals(1L, snapshot.get("batchItemsSucceeded"));
        assertEquals(2L, snapshot.get("batchItemsFailed"));
        assertEquals(1L, snapshot.get("agentRequestSuccess"));
        assertEquals(1L, snapshot.get("agentRequestFailure"));
        assertEquals(1L, snapshot.get("commitConflict"));
        assertEquals(1L, snapshot.get("sseConnections"));
        assertEquals(1L, snapshot.get("exportCount"));
        assertEquals(3L, snapshot.get("outboxPending"));
        assertEquals(3L, snapshot.get("outboxFailed"));
    }

    @Test
    void outboxQueryFailureReportedAsMinusOne() {
        when(outboxMapper.selectCount(any())).thenThrow(new RuntimeException("db down"));
        assertEquals(-1L, metrics.snapshot().get("outboxPending"));
    }
}

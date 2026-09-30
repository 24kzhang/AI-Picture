package com.zys.backend.agent;

import com.zys.backend.agent.service.AgentQueueService;
import com.zys.backend.agent.service.ToolRunService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentQueueServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ToolRunService tools = mock(ToolRunService.class);
    private final AgentQueueService queue = createQueue();

    private AgentQueueService createQueue() {
        AgentQueueService service = new AgentQueueService();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "toolRunService", tools);
        return service;
    }

    @Test
    void runsLocallyWhenConsumerIsUnavailable() {
        queue.enqueue(42L);
        verify(tools, timeout(2000)).executeQueued(42L);
        queue.recoverPending();
        verifyNoInteractions(redis);
    }

    @SuppressWarnings("unchecked")
    @Test
    void healthyStreamsAreConsumedOnlyThroughQueue() {
        StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        when(streams.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));
        queue.setStreamsAvailable(true);
        queue.enqueue(43L);
        verify(streams).add(any(MapRecord.class));
        verifyNoInteractions(tools);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedEnqueueFallsBackToLocalExecution() {
        StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        when(streams.add(any(MapRecord.class))).thenThrow(new IllegalStateException("Redis unavailable"));
        queue.setStreamsAvailable(true);
        queue.enqueue(44L);
        verify(tools, timeout(2000)).executeQueued(44L);
    }
}

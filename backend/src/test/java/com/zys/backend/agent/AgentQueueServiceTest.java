package com.zys.backend.agent;

import com.zys.backend.agent.service.AgentQueueService;
import com.zys.backend.agent.service.ToolRunService;
import com.zys.backend.constant.AgentConstant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

    @Test
    void extractsToolRunIdByByteContentInsteadOfArrayIdentity() {
        ByteRecord record = mock(ByteRecord.class);
        Map<byte[], byte[]> fields = new HashMap<>();
        fields.put(AgentConstant.STREAM_FIELD_TOOL_RUN_ID.getBytes(StandardCharsets.UTF_8),
                "45".getBytes(StandardCharsets.UTF_8));
        when(record.getValue()).thenReturn(fields);

        String extracted = ReflectionTestUtils.invokeMethod(queue, "extractToolRunId", record);

        Assertions.assertEquals("45", extracted);
    }

    @SuppressWarnings("unchecked")
    @Test
    void keepsMessagePendingWhenExecutionIsStillOwnedByAnotherConsumer() {
        StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        MapRecord<String, String, String> record = mock(MapRecord.class);
        RecordId id = RecordId.of("10-0");
        when(record.getId()).thenReturn(id);
        when(record.getValue()).thenReturn(Collections.singletonMap(
                AgentConstant.STREAM_FIELD_TOOL_RUN_ID, "45"));
        when(tools.executeQueued(45L)).thenReturn(false);

        queue.onMessage(record);

        verify(streams, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void acknowledgesMessageAfterExecutionCompletes() {
        StreamOperations<String, String, String> streams = mock(StreamOperations.class);
        when(redis.<String, String>opsForStream()).thenReturn(streams);
        MapRecord<String, String, String> record = mock(MapRecord.class);
        RecordId id = RecordId.of("11-0");
        when(record.getId()).thenReturn(id);
        when(record.getValue()).thenReturn(Collections.singletonMap(
                AgentConstant.STREAM_FIELD_TOOL_RUN_ID, "46"));
        when(tools.executeQueued(46L)).thenReturn(true);

        queue.onMessage(record);

        verify(streams).acknowledge(AgentConstant.TOOL_RUN_STREAM_KEY,
                AgentConstant.TOOL_RUN_CONSUMER_GROUP, id);
    }
}

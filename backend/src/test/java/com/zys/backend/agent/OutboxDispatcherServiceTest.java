package com.zys.backend.agent;

import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.enums.OutboxStatusEnum;
import com.zys.backend.service.PictureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDispatcherServiceTest {

    @Mock
    private IntegrationOutboxMapper outboxMapper;

    @Mock
    private VectorClient vectorClient;

    @Mock
    private PictureService pictureService;

    @InjectMocks
    private OutboxDispatcherService dispatcher;

    private IntegrationOutbox item(String eventType, int retryCount) {
        IntegrationOutbox outbox = new IntegrationOutbox();
        outbox.setId(1L);
        outbox.setEventType(eventType);
        outbox.setAggregateId(100L);
        outbox.setPayload("{}");
        outbox.setStatus(OutboxStatusEnum.PENDING.getValue());
        outbox.setRetryCount(retryCount);
        outbox.setCreateTime(new Date());
        return outbox;
    }

    private void stubPendingBatch(IntegrationOutbox... items) {
        when(outboxMapper.selectList(any())).thenReturn(List.of(items));
        when(outboxMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void vectorReindexSuccessMarksSucceeded() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VECTOR_REINDEX, 0);
        stubPendingBatch(outbox);
        Picture picture = new Picture();
        picture.setId(100L);
        when(pictureService.getById(100L)).thenReturn(picture);

        int processed = dispatcher.dispatchPending();

        assertEquals(1, processed);
        verify(vectorClient).upsert(picture);
        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxStatusEnum.SUCCEEDED.getValue(), captor.getValue().getStatus());
    }

    @Test
    void vectorFailureSchedulesRetryWithBackoff() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VECTOR_REINDEX, 0);
        stubPendingBatch(outbox);
        when(pictureService.getById(100L)).thenReturn(new Picture());
        doThrow(new RuntimeException("向量服务不可用")).when(vectorClient).upsert(any());

        dispatcher.dispatchPending();

        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        IntegrationOutbox updated = captor.getValue();
        assertEquals(OutboxStatusEnum.PENDING.getValue(), updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNotNull(updated.getNextRetryTime());
        assertTrue(updated.getNextRetryTime().after(new Date()));
    }

    @Test
    void retryLimitExceededMarksFailed() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VECTOR_REINDEX, 9);
        stubPendingBatch(outbox);
        when(pictureService.getById(100L)).thenReturn(new Picture());
        doThrow(new RuntimeException("still down")).when(vectorClient).upsert(any());

        dispatcher.dispatchPending();

        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxStatusEnum.FAILED.getValue(), captor.getValue().getStatus());
        assertEquals(10, captor.getValue().getRetryCount());
    }

    @Test
    void missingPictureSkipsReindexGracefully() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VECTOR_REINDEX, 0);
        stubPendingBatch(outbox);
        when(pictureService.getById(100L)).thenReturn(null);

        dispatcher.dispatchPending();

        verify(vectorClient, never()).upsert(any());
        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxStatusEnum.SUCCEEDED.getValue(), captor.getValue().getStatus());
    }

    @Test
    void claimFailureSkipsItem() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VECTOR_REINDEX, 0);
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        // 乐观占用失败（其他实例已抢占）
        when(outboxMapper.update(any(), any())).thenReturn(0);

        int processed = dispatcher.dispatchPending();

        assertEquals(0, processed);
        verify(vectorClient, never()).upsert(any());
        verify(outboxMapper, never()).updateById(any(IntegrationOutbox.class));
    }

    @Test
    void auditEventMarksSucceededWithoutSideEffects() {
        IntegrationOutbox outbox = item(OutboxDispatcherService.EVENT_VERSION_COMMITTED, 0);
        stubPendingBatch(outbox);

        dispatcher.dispatchPending();

        verify(vectorClient, never()).upsert(any());
        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxStatusEnum.SUCCEEDED.getValue(), captor.getValue().getStatus());
    }

    @Test
    void unknownEventCompletesWithoutRetry() {
        IntegrationOutbox outbox = item("SOMETHING_UNKNOWN", 0);
        stubPendingBatch(outbox);

        dispatcher.dispatchPending();

        ArgumentCaptor<IntegrationOutbox> captor = ArgumentCaptor.forClass(IntegrationOutbox.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxStatusEnum.SUCCEEDED.getValue(), captor.getValue().getStatus());
    }
}

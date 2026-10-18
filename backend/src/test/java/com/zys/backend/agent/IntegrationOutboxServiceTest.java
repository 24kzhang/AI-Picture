package com.zys.backend.agent;

import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.agent.service.IntegrationOutboxService;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.enums.OutboxEventTypeEnum;
import com.zys.backend.model.enums.OutboxStatusEnum;
import com.zys.backend.agent.support.AgentTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Outbox 的抢占和宕机恢复测试，验证多实例竞争时不会重复派发。
 */
class IntegrationOutboxServiceTest {

    @BeforeAll
    static void initEntities() {
        AgentTestSupport.initTableInfo(IntegrationOutbox.class);
    }

    private IntegrationOutboxService service;
    private IntegrationOutboxMapper outboxMapper;
    private VectorClient vectorClient;
    private PictureMapper pictureMapper;
    private AgentStorageService storageService;

    @BeforeEach
    void setUp() {
        service = new IntegrationOutboxService();
        outboxMapper = mock(IntegrationOutboxMapper.class);
        vectorClient = mock(VectorClient.class);
        pictureMapper = mock(PictureMapper.class);
        storageService = mock(AgentStorageService.class);
        ReflectionTestUtils.setField(service, "outboxMapper", outboxMapper);
        ReflectionTestUtils.setField(service, "vectorClient", vectorClient);
        ReflectionTestUtils.setField(service, "pictureMapper", pictureMapper);
        ReflectionTestUtils.setField(service, "storageService", storageService);
    }

    @Test
    void doesNotDispatchWhenPendingClaimWasLost() {
        IntegrationOutbox outbox = vectorEvent(1001L);
        when(outboxMapper.update(any(), any())).thenReturn(0);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "process", outbox));

        verifyNoInteractions(vectorClient, pictureMapper, storageService);
    }

    @Test
    void staleProcessingIsRequeuedWithConditionalUpdate() {
        IntegrationOutbox outbox = vectorEvent(1002L);
        outbox.setStatus(OutboxStatusEnum.PROCESSING.getValue());
        outbox.setProcessTime(new Date(System.currentTimeMillis() - 20 * 60 * 1000L));
        outbox.setRetries(1);
        when(outboxMapper.selectList(any())).thenReturn(Collections.singletonList(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(service, "recoverStaleProcessing");

        verify(outboxMapper).update(any(), any());
        verifyNoInteractions(vectorClient, pictureMapper, storageService);
    }

    @Test
    void successfulDispatchMustAlsoOwnProcessingStateOnWriteBack() {
        IntegrationOutbox outbox = vectorEvent(1003L);
        when(outboxMapper.update(any(), any())).thenReturn(1);
        Picture picture = new Picture();
        picture.setId(1003L);
        when(pictureMapper.selectById(1003L)).thenReturn(picture);
        when(vectorClient.isAvailable()).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "process", outbox);

        verify(vectorClient).upsert(picture);
        verify(outboxMapper, times(2)).update(any(), any());
    }

    private IntegrationOutbox vectorEvent(Long pictureId) {
        IntegrationOutbox outbox = new IntegrationOutbox();
        outbox.setId(pictureId);
        outbox.setEventType(OutboxEventTypeEnum.VECTOR_REBUILD.getValue());
        outbox.setPayloadJson("{\"pictureId\":\"" + pictureId + "\"}");
        outbox.setStatus(OutboxStatusEnum.PENDING.getValue());
        outbox.setRetries(0);
        return outbox;
    }
}

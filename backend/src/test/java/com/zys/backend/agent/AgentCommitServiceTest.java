package com.zys.backend.agent;

import com.zys.backend.agent.model.vo.PictureVersionVO;
import com.zys.backend.agent.service.AgentAssetService;
import com.zys.backend.agent.service.AgentAuthService;
import com.zys.backend.agent.service.AgentCommitService;
import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.agent.service.EditLeaseService;
import com.zys.backend.agent.service.IntegrationOutboxService;
import com.zys.backend.agent.service.StoredObject;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.mapper.PictureEditSessionMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import com.zys.backend.model.enums.EditSessionStatusEnum;
import com.zys.backend.service.SpaceService;
import com.zys.backend.agent.support.AgentTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentCommitService：正常提交、版本冲突、空间容量不足、重复提交幂等
 */
class AgentCommitServiceTest {

    private AgentCommitService commitService;
    private PictureMapper pictureMapper;
    private PictureVersionMapper versionMapper;
    private PictureEditSessionMapper sessionMapper;
    private SpaceService spaceService;
    private AgentAssetService assetService;
    private AgentStorageService storageService;
    private IntegrationOutboxService outboxService;
    private EditLeaseService leaseService;
    private TransactionTemplate transactionTemplate;

    private User user;

    @BeforeAll
    static void initEntities() {
        AgentTestSupport.initTableInfo(PictureEditSession.class, PictureVersion.class, Picture.class, PictureAgentAsset.class);
    }

    @BeforeEach
    void setUp() {
        commitService = new AgentCommitService();
        pictureMapper = mock(PictureMapper.class);
        versionMapper = mock(PictureVersionMapper.class);
        sessionMapper = mock(PictureEditSessionMapper.class);
        spaceService = mock(SpaceService.class);
        assetService = mock(AgentAssetService.class);
        storageService = mock(AgentStorageService.class);
        outboxService = mock(IntegrationOutboxService.class);
        leaseService = mock(EditLeaseService.class);
        transactionTemplate = mock(TransactionTemplate.class);

        ReflectionTestUtils.setField(commitService, "pictureMapper", pictureMapper);
        ReflectionTestUtils.setField(commitService, "versionMapper", versionMapper);
        ReflectionTestUtils.setField(commitService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(commitService, "spaceService", spaceService);
        ReflectionTestUtils.setField(commitService, "assetService", assetService);
        ReflectionTestUtils.setField(commitService, "storageService", storageService);
        ReflectionTestUtils.setField(commitService, "outboxService", outboxService);
        ReflectionTestUtils.setField(commitService, "editLeaseService", leaseService);
        ReflectionTestUtils.setField(commitService, "authService", mock(AgentAuthService.class));
        ReflectionTestUtils.setField(commitService, "transactionTemplate", transactionTemplate);

        user = new User();
        user.setId(2L);

        // 事务模板直接执行回调
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private PictureEditSession activeSession() {
        PictureEditSession session = new PictureEditSession();
        session.setId(9L);
        session.setPictureId(100L);
        session.setUserId(2L);
        session.setStatus(EditSessionStatusEnum.ACTIVE.getValue());
        session.setBaseEditVersion(3L);
        session.setFinalAssetId(500L);
        return session;
    }

    private Picture picture(long editVersion) {
        Picture picture = new Picture();
        picture.setId(100L);
        picture.setUrl("https://cos.example.com/old.png");
        picture.setPicSize(1024L);
        picture.setEditVersion(editVersion);
        return picture;
    }

    private PictureAgentAsset finalAsset() {
        PictureAgentAsset asset = new PictureAgentAsset();
        asset.setId(500L);
        asset.setEditSessionId(9L);
        asset.setKind(AgentAssetKindEnum.CANDIDATE.getValue());
        asset.setUrl("https://cos.example.com/agent/d.png");
        return asset;
    }

    private StoredObject stored() {
        return new StoredObject()
                .setUrl("https://cos.example.com/version/new.png")
                .setThumbnailUrl("https://cos.example.com/version/new_thumbnail.jpg")
                .setStorageKey("picture-version/100/new.png")
                .setSizeBytes(2048L)
                .setWidth(800)
                .setHeight(600)
                .setFormat("png");
    }

    private void stubHappyPath() {
        when(assetService.getInSession(9L, 500L)).thenReturn(finalAsset());
        when(pictureMapper.selectById(100L)).thenReturn(picture(3L));
        when(assetService.loadBytes(any())).thenReturn(new byte[]{1, 2, 3});
        when(storageService.storeVersion(anyLong(), anyString(), any())).thenReturn(stored());
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(pictureMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void commitHappyPath() {
        stubHappyPath();
        PictureEditSession session = activeSession();

        PictureVersionVO version = commitService.commit(session, null, 3L, user);

        assertNotNull(version);
        assertEquals(Integer.valueOf(1), version.getVersionNo());
        assertEquals("https://cos.example.com/version/new.png", version.getUrl());

        ArgumentCaptor<PictureVersion> versionCaptor = ArgumentCaptor.forClass(PictureVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertEquals("agent", versionCaptor.getValue().getSource());
        assertEquals(Long.valueOf(9L), versionCaptor.getValue().getSourceSessionId());

        ArgumentCaptor<PictureEditSession> sessionCaptor = ArgumentCaptor.forClass(PictureEditSession.class);
        verify(sessionMapper).updateById(sessionCaptor.capture());
        assertEquals(EditSessionStatusEnum.COMMITTED.getValue(), sessionCaptor.getValue().getStatus());

        verify(outboxService).createVectorRebuild(100L);
        verify(leaseService).release(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void commitRejectsVersionConflictAndMarksSession() {
        when(assetService.getInSession(9L, 500L)).thenReturn(finalAsset());
        when(pictureMapper.selectById(100L)).thenReturn(picture(7L));
        PictureEditSession session = activeSession();

        BusinessException error = assertThrows(BusinessException.class,
                () -> commitService.commit(session, null, 3L, user));
        assertTrue(error.getMessage().contains("冲突"));

        ArgumentCaptor<PictureEditSession> captor = ArgumentCaptor.forClass(PictureEditSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertEquals(EditSessionStatusEnum.CONFLICT.getValue(), captor.getValue().getStatus());
        // 冲突发生在 COS 写入之前
        verify(storageService, never()).storeVersion(anyLong(), anyString(), any());
    }

    @Test
    void commitRejectsInsufficientSpaceCapacity() {
        when(assetService.getInSession(9L, 500L)).thenReturn(finalAsset());
        Picture picture = picture(3L);
        picture.setSpaceId(1001L);
        when(pictureMapper.selectById(100L)).thenReturn(picture);
        Space space = new Space();
        space.setId(1001L);
        space.setTotalSize(900L);
        space.setMaxSize(1500L);
        when(spaceService.getById(1001L)).thenReturn(space);
        when(assetService.loadBytes(any())).thenReturn(new byte[4096]);
        PictureEditSession session = activeSession();

        BusinessException error = assertThrows(BusinessException.class,
                () -> commitService.commit(session, null, 3L, user));
        assertTrue(error.getMessage().contains("容量"));
        verify(storageService, never()).storeVersion(anyLong(), anyString(), any());
    }

    @Test
    void commitIsIdempotentForCommittedSession() {
        PictureEditSession session = activeSession();
        session.setStatus(EditSessionStatusEnum.COMMITTED.getValue());
        session.setCommittedVersionId(66L);
        PictureVersion existing = new PictureVersion();
        existing.setId(66L);
        existing.setPictureId(100L);
        existing.setVersionNo(4);
        when(versionMapper.selectById(66L)).thenReturn(existing);

        PictureVersionVO version = commitService.commit(session, null, 3L, user);

        assertEquals(Integer.valueOf(4), version.getVersionNo());
        verify(storageService, never()).storeVersion(anyLong(), anyString(), any());
        verify(pictureMapper, never()).update(any(), any());
    }

    @Test
    void optimisticLockFailureCleansOrphanObject() {
        stubHappyPath();
        when(pictureMapper.update(any(), any())).thenReturn(0);
        when(storageService.deleteQuietly(anyString())).thenReturn(true);
        PictureEditSession session = activeSession();

        BusinessException error = assertThrows(BusinessException.class,
                () -> commitService.commit(session, null, 3L, user));
        assertTrue(error.getMessage().contains("冲突"));

        ArgumentCaptor<PictureEditSession> captor = ArgumentCaptor.forClass(PictureEditSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertEquals(EditSessionStatusEnum.CONFLICT.getValue(), captor.getValue().getStatus());
        verify(storageService).deleteQuietly("picture-version/100/new.png");
    }

    @Test
    void orphanCleanupFailureWritesOutboxEvent() {
        stubHappyPath();
        when(pictureMapper.update(any(), any())).thenReturn(0);
        when(storageService.deleteQuietly(anyString())).thenReturn(false);
        PictureEditSession session = activeSession();

        assertThrows(BusinessException.class, () -> commitService.commit(session, null, 3L, user));
        verify(outboxService).createOrphanCleanup(anyString(), anyString());
    }
}

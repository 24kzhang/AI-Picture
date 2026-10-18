package com.zys.backend.service;

import com.zys.backend.agent.service.IntegrationOutboxService;
import com.zys.backend.agent.support.AgentTestSupport;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.manager.upload.FilePictureUpload;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.mapper.PictureVersionMapper;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.model.dto.file.UploadPictureResult;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.PictureVersion;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.impl.PictureServiceImpl;
import com.zys.backend.model.dto.picture.PictureUploadRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 图片对象引用和入库失败回滚测试，覆盖历史版本仍引用、当前记录仍引用和 COS 清理失败。
 */
class PictureServiceImplConcurrencyTest {

    private PictureServiceImpl service;
    private PictureMapper pictureMapper;
    private PictureVersionMapper versionMapper;
    private CosStorageManager storageManager;
    private IntegrationOutboxService outboxService;

    @BeforeAll
    static void initEntities() {
        AgentTestSupport.initTableInfo(Picture.class, PictureVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new PictureServiceImpl();
        pictureMapper = mock(PictureMapper.class);
        versionMapper = mock(PictureVersionMapper.class);
        storageManager = mock(CosStorageManager.class);
        outboxService = mock(IntegrationOutboxService.class);
        ReflectionTestUtils.setField(service, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(service, "pictureVersionMapper", versionMapper);
        ReflectionTestUtils.setField(service, "cosStorageManager", storageManager);
        ReflectionTestUtils.setField(service, "integrationOutboxService", outboxService);
    }

    @Test
    void keepsObjectWhenAnActiveVersionStillReferencesIt() {
        Picture old = picture("https://cos.example.com/old.png", "https://cos.example.com/old-thumb.jpg");
        when(pictureMapper.selectCount(any())).thenReturn(0L);
        when(versionMapper.selectCount(any())).thenReturn(1L, 0L);
        when(storageManager.deleteByUrl(anyString())).thenReturn(true);

        service.clearPictureFile(old);

        verify(storageManager, never()).deleteByUrl(old.getUrl());
        verify(storageManager).deleteByUrl(old.getThumbnailUrl());
    }

    @Test
    void keepsObjectWhenAnotherCurrentPictureReferencesIt() {
        Picture old = picture("https://cos.example.com/shared.png", "https://cos.example.com/shared-thumb.jpg");
        when(pictureMapper.selectCount(any())).thenReturn(1L, 0L);
        when(versionMapper.selectCount(any())).thenReturn(0L);
        when(storageManager.deleteByUrl(anyString())).thenReturn(true);

        service.clearPictureFile(old);

        verify(storageManager, never()).deleteByUrl(old.getUrl());
        verify(storageManager).deleteByUrl(old.getThumbnailUrl());
    }

    @Test
    void recordsOutboxWhenUploadTransactionRollsBackAndDeleteFails() {
        FilePictureUpload fileUpload = mock(FilePictureUpload.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        VectorClient vectorClient = mock(VectorClient.class);
        UploadPictureResult uploaded = new UploadPictureResult();
        uploaded.setUrl("https://cos.example.com/new.png");
        uploaded.setThumbnailUrl("https://cos.example.com/new-thumb.jpg");
        uploaded.setPicName("new");
        uploaded.setPicSize(10L);
        when(fileUpload.uploadPicture(any(), eq("public/9"))).thenReturn(uploaded);
        doThrow(new RuntimeException("db unavailable"))
                .when(transactionTemplate).execute(any(org.springframework.transaction.support.TransactionCallback.class));
        when(storageManager.deleteByUrl(uploaded.getUrl())).thenReturn(false);
        when(storageManager.deleteByUrl(uploaded.getThumbnailUrl())).thenReturn(true);

        ReflectionTestUtils.setField(service, "filePictureUpload", fileUpload);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "vectorClient", vectorClient);
        User user = new User();
        user.setId(9L);

        assertThrows(RuntimeException.class, () -> service.uploadPicture(new Object(), new PictureUploadRequest(), user));

        verify(storageManager).deleteByUrl(uploaded.getUrl());
        verify(storageManager).deleteByUrl(uploaded.getThumbnailUrl());
        verify(outboxService).createOrphanCleanup(eq(uploaded.getUrl()), contains("事务失败"));
        verifyNoInteractions(vectorClient);
    }

    private Picture picture(String url, String thumbnail) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setUrl(url);
        picture.setThumbnailUrl(thumbnail);
        picture.setEditVersion(1L);
        picture.setPicSize(10L);
        picture.setCreateTime(new Date());
        return picture;
    }
}

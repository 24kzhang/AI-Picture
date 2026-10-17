package com.zys.backend.moderation;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.entity.Picture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.Date;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PictureModerationServiceTest {
    @Test void completedReviewIsConditionalOnTheOriginalPendingSnapshot() throws Exception {
        PictureModerationService service=new PictureModerationService();
        JdbcTemplate jdbc=mock(JdbcTemplate.class);CosStorageManager storage=mock(CosStorageManager.class);QwenModerationClient model=mock(QwenModerationClient.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"storage",storage);ReflectionTestUtils.setField(service,"model",model);
        CosStorageManager.ManagedImageFile file=mock(CosStorageManager.ManagedImageFile.class);Path path=Path.of("product.jpg");
        when(storage.materialize("product.jpg")).thenReturn(file);when(file.getPath()).thenReturn(path);when(model.review(path)).thenReturn(new QwenModerationClient.Result(1,"通过"));
        Picture snapshot=new Picture();snapshot.setId(1L);snapshot.setUrl("product.jpg");snapshot.setReviewTime(new Date(1000));snapshot.setUpdateTime(new Date(2000));
        service.review(snapshot);
        verify(jdbc).update(contains("reviewStatus=0 AND reviewerId=0 AND url=? AND reviewTime<=>? AND updateTime<=>?"),eq(1),eq("通过"),eq(1L),eq("product.jpg"),eq(snapshot.getReviewTime()),eq(snapshot.getUpdateTime()));
        verify(file).close();service.stop();
    }
    @Test void unavailableModelLeavesThePicturePending() {
        PictureModerationService service=new PictureModerationService();JdbcTemplate jdbc=mock(JdbcTemplate.class);CosStorageManager storage=mock(CosStorageManager.class);
        ReflectionTestUtils.setField(service,"jdbc",jdbc);ReflectionTestUtils.setField(service,"storage",storage);
        when(storage.materialize(anyString())).thenThrow(new IllegalStateException("temporary outage"));
        Picture picture=new Picture();picture.setId(1L);picture.setUrl("product.jpg");service.review(picture);
        verify(jdbc).update(anyString(),eq(0),contains("等待重试"),eq(1L),eq("product.jpg"),isNull(),isNull());service.stop();
    }
}

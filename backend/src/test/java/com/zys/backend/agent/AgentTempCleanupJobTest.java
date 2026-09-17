package com.zys.backend.agent;

import com.zys.backend.manager.CosStorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTempCleanupJobTest {

    @Mock
    private CosStorageManager cosStorageManager;

    @Mock
    private AgentMetrics agentMetrics;

    @InjectMocks
    private AgentTempCleanupJob job;

    private CosStorageManager.CosObjectInfo object(String key, long ageDays) {
        CosStorageManager.CosObjectInfo info = new CosStorageManager.CosObjectInfo();
        info.setKey(key);
        info.setSize(1024);
        info.setLastModified(new Date(System.currentTimeMillis() - ageDays * 24 * 3600 * 1000L));
        return info;
    }

    @Test
    void deletesOnlyExpiredTempObjects() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        List<CosStorageManager.CosObjectInfo> objects = new ArrayList<>();
        objects.add(object("agent-temp/u1/old.png", 10));
        objects.add(object("agent-temp/u1/fresh.png", 1));
        when(cosStorageManager.listObjects(eq("agent-temp/"), anyInt())).thenReturn(objects);

        job.cleanupExpiredTempObjects();

        verify(cosStorageManager).deleteKeyQuietly("agent-temp/u1/old.png");
        verify(cosStorageManager, never()).deleteKeyQuietly("agent-temp/u1/fresh.png");
        verify(agentMetrics).addCleanupDeleted(1);
    }

    @Test
    void skipsWhenDisabled() {
        ReflectionTestUtils.setField(job, "enabled", false);
        job.cleanupExpiredTempObjects();
        verify(cosStorageManager, never()).listObjects(anyString(), anyInt());
    }

    @Test
    void countsFailuresWithoutAborting() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        List<CosStorageManager.CosObjectInfo> objects = new ArrayList<>();
        objects.add(object("agent-temp/u1/broken.png", 30));
        objects.add(object("agent-temp/u1/ok.png", 30));
        when(cosStorageManager.listObjects(eq("agent-temp/"), anyInt())).thenReturn(objects);
        doThrow(new RuntimeException("COS 异常")).when(cosStorageManager)
                .deleteKeyQuietly("agent-temp/u1/broken.png");

        job.cleanupExpiredTempObjects();

        verify(agentMetrics).incrementCleanupFailure();
        verify(agentMetrics).addCleanupDeleted(1);
    }

    @Test
    void ignoresObjectsWithoutTimestamp() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        CosStorageManager.CosObjectInfo noTime = new CosStorageManager.CosObjectInfo();
        noTime.setKey("agent-temp/u1/unknown.png");
        List<CosStorageManager.CosObjectInfo> objects = new ArrayList<>();
        objects.add(noTime);
        when(cosStorageManager.listObjects(eq("agent-temp/"), anyInt())).thenReturn(objects);

        job.cleanupExpiredTempObjects();

        verify(cosStorageManager, never()).deleteKeyQuietly(any());
        assertEquals(0L, (long) 0L);
    }
}

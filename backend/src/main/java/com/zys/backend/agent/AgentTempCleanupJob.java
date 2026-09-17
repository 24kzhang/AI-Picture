package com.zys.backend.agent;

import com.zys.backend.manager.CosStorageManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Agent 临时对象（agent-temp/ 前缀）生命周期管理。
 * 正式版本对象（pictures/）不会被清理；临时对象默认保留 7 天。
 */
@Slf4j
@Service
public class AgentTempCleanupJob {

    public static final String TEMP_PREFIX = "agent-temp/";
    private static final int MAX_SCAN = 1000;

    @Resource
    private CosStorageManager cosStorageManager;

    @Resource
    private AgentMetrics agentMetrics;

    @Value("${gallery.retouch.cleanup.enabled:true}")
    private boolean enabled;

    @Value("${gallery.retouch.cleanup.retention-days:7}")
    private int retentionDays;

    /**
     * 每日清理过期的 Agent 临时对象
     */
    @Scheduled(cron = "${gallery.retouch.cleanup.cron:0 30 3 * * ?}")
    public void cleanupExpiredTempObjects() {
        if (!enabled) {
            return;
        }
        int deleted = 0;
        int failed = 0;
        Instant deadline = Instant.now().minus(Duration.ofDays(retentionDays));
        List<CosStorageManager.CosObjectInfo> objects = cosStorageManager.listObjects(TEMP_PREFIX, MAX_SCAN);
        for (CosStorageManager.CosObjectInfo object : objects) {
            Date lastModified = object.getLastModified();
            if (lastModified == null || lastModified.toInstant().isAfter(deadline)) {
                continue;
            }
            try {
                cosStorageManager.deleteKeyQuietly(object.getKey());
                deleted++;
            } catch (Exception e) {
                failed++;
                agentMetrics.incrementCleanupFailure();
                log.warn("临时对象清理失败，key={}：{}", object.getKey(), e.getMessage());
            }
        }
        if (deleted > 0) {
            agentMetrics.addCleanupDeleted(deleted);
        }
        log.info("Agent 临时对象清理完成：扫描 {} 个，删除 {} 个，失败 {} 个（保留 {} 天）",
                objects.size(), deleted, failed, retentionDays);
    }
}

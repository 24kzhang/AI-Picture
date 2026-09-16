package com.zys.backend.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.enums.OutboxStatusEnum;
import com.zys.backend.service.PictureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 集成事务 Outbox 分发器：图片版本提交成功后，异步完成向量重建等外部副作用。
 * 外部服务失败不回滚已提交的图片版本，按退避策略重试，超限标记 FAILED。
 */
@Slf4j
@Service
public class OutboxDispatcherService {

    public static final String EVENT_VERSION_COMMITTED = "PICTURE_VERSION_COMMITTED";
    public static final String EVENT_THUMBNAIL_REQUIRED = "PICTURE_THUMBNAIL_REQUIRED";
    public static final String EVENT_VECTOR_REINDEX = "PICTURE_VECTOR_REINDEX_REQUIRED";

    private static final int BATCH_SIZE = 20;
    private static final int MAX_RETRY = 10;
    private static final long MAX_BACKOFF_SECONDS = 1800;

    @Resource
    private IntegrationOutboxMapper outboxMapper;

    @Resource
    private VectorClient vectorClient;

    @Resource
    private PictureService pictureService;

    @Value("${gallery.retouch.outbox.enabled:true}")
    private boolean enabled;

    /**
     * 定时拉取待处理事件
     */
    @Scheduled(fixedDelayString = "${gallery.retouch.outbox.poll-interval-ms:5000}",
            initialDelayString = "${gallery.retouch.outbox.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            dispatchPending();
        } catch (Exception e) {
            log.warn("Outbox 轮询异常：{}", e.getMessage());
        }
    }

    /**
     * 拉取并处理一批待处理事件，返回处理条数
     */
    public int dispatchPending() {
        List<IntegrationOutbox> batch = outboxMapper.selectList(
                new QueryWrapper<IntegrationOutbox>()
                        .eq("status", OutboxStatusEnum.PENDING.getValue())
                        .and(w -> w.isNull("nextRetryTime")
                                .or().le("nextRetryTime", new Date()))
                        .orderByAsc("createTime")
                        .last("limit " + BATCH_SIZE));
        int processed = 0;
        for (IntegrationOutbox item : batch) {
            if (claim(item)) {
                handle(item);
                processed++;
            }
        }
        return processed;
    }

    /**
     * PENDING → PROCESSING 乐观占用，防止多实例重复处理
     */
    private boolean claim(IntegrationOutbox item) {
        UpdateWrapper<IntegrationOutbox> wrapper = new UpdateWrapper<IntegrationOutbox>()
                .eq("id", item.getId())
                .eq("status", OutboxStatusEnum.PENDING.getValue())
                .set("status", OutboxStatusEnum.PROCESSING.getValue())
                .set("updateTime", new Date());
        return outboxMapper.update(null, wrapper) > 0;
    }

    /**
     * 按事件类型分发；未知类型直接完结，避免无限重试
     */
    void handle(IntegrationOutbox item) {
        try {
            switch (item.getEventType() == null ? "" : item.getEventType()) {
                case EVENT_VECTOR_REINDEX:
                    reindexVector(item.getAggregateId());
                    break;
                case EVENT_VERSION_COMMITTED:
                    // 审计事件：版本已随提交事务落库，这里仅记录
                    log.info("图片版本提交事件：pictureId={}，payload={}",
                            item.getAggregateId(), item.getPayload());
                    break;
                case EVENT_THUMBNAIL_REQUIRED:
                    // 缩略图在提交事务前已同步生成，此处仅审计兜底
                    log.info("缩略图事件（已在提交时同步生成）：pictureId={}", item.getAggregateId());
                    break;
                default:
                    log.warn("未知 Outbox 事件类型 {}，直接完结：id={}", item.getEventType(), item.getId());
                    break;
            }
            markSucceeded(item);
        } catch (Exception e) {
            log.warn("Outbox 事件处理失败，id={}，type={}：{}",
                    item.getId(), item.getEventType(), e.getMessage());
            markRetry(item);
        }
    }

    private void reindexVector(Long pictureId) {
        Picture picture = pictureService.getById(pictureId);
        if (picture == null) {
            log.warn("图片 {} 不存在，跳过向量重建", pictureId);
            return;
        }
        vectorClient.upsert(picture);
    }

    private void markSucceeded(IntegrationOutbox item) {
        item.setStatus(OutboxStatusEnum.SUCCEEDED.getValue());
        item.setNextRetryTime(null);
        item.setUpdateTime(new Date());
        outboxMapper.updateById(item);
    }

    private void markRetry(IntegrationOutbox item) {
        int retry = item.getRetryCount() == null ? 0 : item.getRetryCount();
        retry++;
        item.setRetryCount(retry);
        if (retry >= MAX_RETRY) {
            item.setStatus(OutboxStatusEnum.FAILED.getValue());
            log.error("Outbox 事件重试超限，标记失败：id={}，type={}", item.getId(), item.getEventType());
        } else {
            item.setStatus(OutboxStatusEnum.PENDING.getValue());
            long backoffSeconds = Math.min(60L * retry, MAX_BACKOFF_SECONDS);
            item.setNextRetryTime(new Date(System.currentTimeMillis() + backoffSeconds * 1000));
        }
        item.setUpdateTime(new Date());
        outboxMapper.updateById(item);
    }
}

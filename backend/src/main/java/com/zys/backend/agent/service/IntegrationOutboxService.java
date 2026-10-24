package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.mapper.IntegrationOutboxMapper;
import com.zys.backend.mapper.PictureMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.enums.OutboxEventTypeEnum;
import com.zys.backend.model.enums.OutboxStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 集成事件发件箱：版本提交后的向量重建、孤儿对象清理等最终一致任务。
 * 事件与业务在同一事务落库，由定时器异步执行、失败重试、超限标记人工核查。
 */
@Slf4j
@Service
public class IntegrationOutboxService {

    private static final int MAX_RETRIES = 5;
    private static final int BATCH_SIZE = 20;
    /** 处理实例失联超过该时间后允许其他实例回收并重试。 */
    private static final long PROCESSING_TIMEOUT_MS = 10 * 60 * 1000L;

    @Resource
    private IntegrationOutboxMapper outboxMapper;

    @Resource
    private VectorClient vectorClient;

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private AgentStorageService storageService;

    /**
     * 写入事件（调用方保证与业务同事务）
     */
    public IntegrationOutbox create(String eventType, String bizId, JSONObject payload) {
        IntegrationOutbox outbox = new IntegrationOutbox();
        outbox.setEventType(eventType);
        outbox.setBizId(bizId);
        outbox.setPayloadJson(payload == null ? null : payload.toString());
        outbox.setStatus(OutboxStatusEnum.PENDING.getValue());
        outbox.setRetries(0);
        outbox.setCreateTime(new Date());
        outboxMapper.insert(outbox);
        return outbox;
    }

    public void createVectorRebuild(Long pictureId) {
        create(OutboxEventTypeEnum.VECTOR_REBUILD.getValue(), String.valueOf(pictureId),
                new JSONObject().set("pictureId", String.valueOf(pictureId)));
    }

    public void createOrphanCleanup(String storageKeyOrUrl, String reason) {
        create(OutboxEventTypeEnum.ORPHAN_CLEANUP.getValue(), null,
                new JSONObject().set("target", storageKeyOrUrl).set("reason", reason));
    }

    /**
     * 定时消费待发事件
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void processPending() {
        recoverStaleProcessing();
        List<IntegrationOutbox> pending = outboxMapper.selectList(Wrappers.<IntegrationOutbox>lambdaQuery()
                .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PENDING.getValue())
                .orderByAsc(IntegrationOutbox::getCreateTime)
                .last("limit " + BATCH_SIZE));
        for (IntegrationOutbox outbox : pending) {
            process(outbox);
        }
    }

    /**
     * 处理超过重试上限仍失败的事件（人工核查兜底），避免无限重试
     */
    @Scheduled(fixedDelay = 120_000L, initialDelay = 60_000L)
    public void requeueFailed() {
        List<IntegrationOutbox> failed = outboxMapper.selectList(Wrappers.<IntegrationOutbox>lambdaQuery()
                .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.FAILED.getValue())
                .lt(IntegrationOutbox::getRetries, MAX_RETRIES)
                .last("limit " + BATCH_SIZE));
        for (IntegrationOutbox outbox : failed) {
            int changed = outboxMapper.update(null, Wrappers.<IntegrationOutbox>lambdaUpdate()
                    .eq(IntegrationOutbox::getId, outbox.getId())
                    .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.FAILED.getValue())
                    .lt(IntegrationOutbox::getRetries, MAX_RETRIES)
                    .set(IntegrationOutbox::getStatus, OutboxStatusEnum.PENDING.getValue())
                    .set(IntegrationOutbox::getProcessTime, new Date()));
            if (changed == 0) {
                log.debug("Outbox 失败事件已被其他实例处理，跳过重新入队，id={}", outbox.getId());
            }
        }
    }

    /**
     * 回收实例宕机留下的 processing 事件。外部派发必须保持幂等，
     * 因为进程可能在派发成功后、状态回写前崩溃。
     */
    private void recoverStaleProcessing() {
        Date cutoff = new Date(System.currentTimeMillis() - PROCESSING_TIMEOUT_MS);
        List<IntegrationOutbox> processing = outboxMapper.selectList(Wrappers.<IntegrationOutbox>lambdaQuery()
                .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PROCESSING.getValue())
                .and(wrapper -> wrapper.isNull(IntegrationOutbox::getProcessTime)
                        .or()
                        .lt(IntegrationOutbox::getProcessTime, cutoff))
                .orderByAsc(IntegrationOutbox::getProcessTime)
                .last("limit " + BATCH_SIZE));
        if (processing == null || processing.isEmpty()) {
            return;
        }
        for (IntegrationOutbox outbox : processing) {
            int retries = (outbox.getRetries() == null ? 0 : outbox.getRetries()) + 1;
            String status = retries >= MAX_RETRIES
                    ? OutboxStatusEnum.FAILED.getValue() : OutboxStatusEnum.PENDING.getValue();
            int changed = outboxMapper.update(null, Wrappers.<IntegrationOutbox>lambdaUpdate()
                    .eq(IntegrationOutbox::getId, outbox.getId())
                    .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PROCESSING.getValue())
                    .and(wrapper -> wrapper.isNull(IntegrationOutbox::getProcessTime)
                            .or()
                            .lt(IntegrationOutbox::getProcessTime, cutoff))
                    .set(IntegrationOutbox::getStatus, status)
                    .set(IntegrationOutbox::getRetries, retries)
                    .set(IntegrationOutbox::getProcessTime, new Date())
                    .set(IntegrationOutbox::getErrorMessage, "处理实例超时，事件已回收"));
            if (changed == 0) {
                log.debug("Outbox processing 回收抢占失败，id={}", outbox.getId());
            }
        }
    }

    private void process(IntegrationOutbox outbox) {
        int claimed = outboxMapper.update(null, Wrappers.<IntegrationOutbox>lambdaUpdate()
                .eq(IntegrationOutbox::getId, outbox.getId())
                .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PENDING.getValue())
                .set(IntegrationOutbox::getStatus, OutboxStatusEnum.PROCESSING.getValue())
                .set(IntegrationOutbox::getProcessTime, new Date()));
        if (claimed != 1) {
            // 多实例同时扫描时，只有成功把 pending 改为 processing 的实例可以派发。
            return;
        }
        try {
            dispatch(outbox);
            int changed = outboxMapper.update(null, Wrappers.<IntegrationOutbox>lambdaUpdate()
                    .eq(IntegrationOutbox::getId, outbox.getId())
                    .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PROCESSING.getValue())
                    .set(IntegrationOutbox::getStatus, OutboxStatusEnum.SUCCEEDED.getValue())
                    .set(IntegrationOutbox::getErrorMessage, null));
            if (changed == 0) {
                log.debug("Outbox 成功回写未抢到 processing 状态，id={}", outbox.getId());
            }
        } catch (Exception e) {
            int retries = (outbox.getRetries() == null ? 0 : outbox.getRetries()) + 1;
            String status = retries >= MAX_RETRIES
                    ? OutboxStatusEnum.FAILED.getValue() : OutboxStatusEnum.PENDING.getValue();
            log.warn("Outbox 事件处理失败，id={}，type={}，retries={}", outbox.getId(), outbox.getEventType(), retries, e);
            int changed = outboxMapper.update(null, Wrappers.<IntegrationOutbox>lambdaUpdate()
                    .eq(IntegrationOutbox::getId, outbox.getId())
                    .eq(IntegrationOutbox::getStatus, OutboxStatusEnum.PROCESSING.getValue())
                    .set(IntegrationOutbox::getStatus, status)
                    .set(IntegrationOutbox::getRetries, retries)
                    .set(IntegrationOutbox::getProcessTime, new Date())
                    .set(IntegrationOutbox::getErrorMessage, StrUtil.maxLength(e.getMessage(), 400)));
            if (changed == 0) {
                log.debug("Outbox 失败回写未抢到 processing 状态，id={}", outbox.getId());
            }
        }
    }

    private void dispatch(IntegrationOutbox outbox) {
        OutboxEventTypeEnum type = OutboxEventTypeEnum.getEnumByValue(outbox.getEventType());
        if (type == null) {
            throw new IllegalStateException("未知 Outbox 事件类型：" + outbox.getEventType());
        }
        JSONObject payload = StrUtil.isBlank(outbox.getPayloadJson())
                ? new JSONObject() : JSONUtil.parseObj(outbox.getPayloadJson());
        switch (type) {
            case VECTOR_REBUILD: {
                Long pictureId = payload.getLong("pictureId");
                if (pictureId == null) {
                    return;
                }
                Picture picture = pictureMapper.selectById(pictureId);
                if (picture == null) {
                    vectorClient.deleteStrict(pictureId);
                    return;
                }
                if (!vectorClient.isAvailable()) {
                    throw new IllegalStateException("向量服务不可用，稍后重试");
                }
                vectorClient.upsert(picture);
                return;
            }
            case ORPHAN_CLEANUP: {
                String target = payload.getStr("target");
                if (StrUtil.isBlank(target)) {
                    return;
                }
                if (!storageService.deleteQuietly(target)) {
                    throw new IllegalStateException("孤儿对象删除失败：" + target);
                }
                return;
            }
            case THUMBNAIL_REBUILD:
                // 缩略图在提交流程内同步生成，此处仅作为人工核查入口
                log.info("thumbnail_rebuild 事件占位处理，payload={}", payload);
                return;
            default:
        }
    }
}

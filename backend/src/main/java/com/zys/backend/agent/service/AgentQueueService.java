package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.constant.AgentConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 Redis Streams 的工具任务队列：入队、消费回调、pending 恢复。
 *
 * <p>消费失败（异常抛出）不 ack，消息保留在 pending；
 * 定时任务认领长时间 pending 的消息，投递次数超限直接写 failed。</p>
 */
@Slf4j
@Service
public class AgentQueueService {

    /**
     * pending 消息空闲超过该时间后被认领重放
     */
    private static final long CLAIM_IDLE_MS = 2 * 60 * 1000L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Lazy
    private ToolRunService toolRunService;

    /**
     * 本机消费者名
     */
    private final String consumerName = buildConsumerName();

    /**
     * 入队一个异步工具任务
     */
    public void enqueue(Long toolRunId) {
        MapRecord<String, String, String> record = MapRecord.create(AgentConstant.TOOL_RUN_STREAM_KEY,
                java.util.Collections.singletonMap(AgentConstant.STREAM_FIELD_TOOL_RUN_ID, String.valueOf(toolRunId)));
        stringRedisTemplate.opsForStream().add(record);
        log.debug("工具任务入队，toolRunId={}", toolRunId);
    }

    /**
     * Stream 消费回调：处理成功或消息非法时 ack；
     * 处理异常保留 pending，由定时任务认领重试。
     */
    public void onMessage(MapRecord<String, String, String> record) {
        RecordId recordId = record.getId();
        String rawId = record.getValue() == null ? null : record.getValue().get(AgentConstant.STREAM_FIELD_TOOL_RUN_ID);
        if (StrUtil.isBlank(rawId)) {
            ack(recordId);
            return;
        }
        Long toolRunId;
        try {
            toolRunId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            log.warn("Stream 消息缺少合法 toolRunId：{}", record.getValue());
            ack(recordId);
            return;
        }
        toolRunService.executeQueued(toolRunId);
        ack(recordId);
    }

    /**
     * 确认消息
     */
    public void ack(RecordId recordId) {
        try {
            stringRedisTemplate.opsForStream()
                    .acknowledge(AgentConstant.TOOL_RUN_STREAM_KEY, AgentConstant.TOOL_RUN_CONSUMER_GROUP, recordId);
        } catch (Exception e) {
            log.warn("Stream ack 失败，recordId={}", recordId, e);
        }
    }

    /**
     * 周期性认领长时间 pending 的消息（消费者宕机、处理异常场景）：
     * 投递次数超限 → 任务写 failed 并 ack；否则重放执行。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void recoverPending() {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    AgentConstant.TOOL_RUN_STREAM_KEY,
                    AgentConstant.TOOL_RUN_CONSUMER_GROUP,
                    Range.unbounded(),
                    50L);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<PendingMessage> stale = new ArrayList<>();
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().toMillis() >= CLAIM_IDLE_MS) {
                    stale.add(message);
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            RecordId[] ids = new RecordId[stale.size()];
            for (int i = 0; i < stale.size(); i++) {
                ids[i] = stale.get(i).getId();
            }
            List<ByteRecord> claimed = stringRedisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                    connection.streamCommands().xClaim(
                            AgentConstant.TOOL_RUN_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                            AgentConstant.TOOL_RUN_CONSUMER_GROUP,
                            consumerName,
                            Duration.ofMillis(CLAIM_IDLE_MS),
                            ids));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            for (int i = 0; i < stale.size(); i++) {
                long deliveryCount = stale.get(i).getTotalDeliveryCount();
                ByteRecord record = i < claimed.size() ? claimed.get(i) : null;
                String rawId = extractToolRunId(record);
                if (StrUtil.isBlank(rawId)) {
                    ack(stale.get(i).getId());
                    continue;
                }
                if (deliveryCount > AgentConstant.MAX_TOOL_RUN_RETRIES) {
                    log.warn("工具任务重试超限，标记失败并 ack，toolRunId={}，deliveryCount={}", rawId, deliveryCount);
                    try {
                        toolRunService.failIfOpen(Long.parseLong(rawId),
                                "任务多次执行失败，已终止（投递 " + deliveryCount + " 次）");
                    } catch (Exception e) {
                        log.warn("标记超限任务失败异常，toolRunId={}", rawId, e);
                    }
                    ack(stale.get(i).getId());
                    continue;
                }
                try {
                    log.info("重放 pending 工具任务，toolRunId={}，deliveryCount={}", rawId, deliveryCount);
                    toolRunService.executeQueued(Long.parseLong(rawId));
                } catch (Exception e) {
                    log.error("重放 pending 工具任务异常，toolRunId={}", rawId, e);
                }
                ack(stale.get(i).getId());
            }
        } catch (Exception e) {
            log.debug("扫描 pending 工具任务失败：{}", e.getMessage());
        }
    }

    private String extractToolRunId(ByteRecord record) {
        if (record == null || record.getValue() == null) {
            return null;
        }
        Map<byte[], byte[]> value = record.getValue();
        byte[] key = AgentConstant.STREAM_FIELD_TOOL_RUN_ID.getBytes(StandardCharsets.UTF_8);
        byte[] raw = value.get(key);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * 供监听容器使用的消费者标识
     */
    public Consumer consumer() {
        return Consumer.from(AgentConstant.TOOL_RUN_CONSUMER_GROUP, consumerName);
    }

    private static String buildConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "gallery-node";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

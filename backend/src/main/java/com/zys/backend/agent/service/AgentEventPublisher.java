package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.AgentRunEventVO;
import com.zys.backend.constant.AgentConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 实时事件发布：Redis Pub/Sub 广播进度，SSE 先返回快照再监听事件。
 */
@Slf4j
@Service
public class AgentEventPublisher {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Qualifier("agentRedisListenerContainer")
    private RedisMessageListenerContainer listenerContainer;

    /**
     * runId -> 本机 SSE 连接
     */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * runId -> 已注册的 Redis 监听（避免重复订阅）
     */
    private final Map<Long, AgentChannelListener> listeners = new ConcurrentHashMap<>();

    /**
     * 发布运行事件（跨节点广播）
     */
    public void publish(Long runId, AgentRunEventVO event) {
        if (runId == null || event == null) {
            return;
        }
        event.setRunId(runId);
        if (event.getTimestamp() == null) {
            event.setTimestamp(System.currentTimeMillis());
        }
        try {
            stringRedisTemplate.convertAndSend(channel(runId), JSONUtil.toJsonStr(event));
        } catch (Exception e) {
            log.warn("发布 Agent 事件失败，runId={}，event={}", runId, event.getEvent(), e);
        }
    }

    /**
     * 订阅某个运行的 SSE 流。首包快照由调用方在返回前写入。
     */
    public SseEmitter subscribe(Long runId, AgentRunEventVO snapshot) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        List<SseEmitter> bucket = emitters.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
        bucket.add(emitter);
        ensureRedisSubscription(runId);
        if (snapshot != null) {
            sendQuietly(emitter, snapshot);
        }
        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> removeEmitter(runId, emitter));
        emitter.onError(e -> removeEmitter(runId, emitter));
        return emitter;
    }

    private void ensureRedisSubscription(Long runId) {
        listeners.computeIfAbsent(runId, key -> {
            AgentChannelListener listener = new AgentChannelListener(key);
            listenerContainer.addMessageListener(listener, new ChannelTopic(channel(key)));
            return listener;
        });
    }

    private void removeEmitter(Long runId, SseEmitter emitter) {
        List<SseEmitter> bucket = emitters.get(runId);
        if (bucket == null) {
            return;
        }
        bucket.remove(emitter);
        if (bucket.isEmpty()) {
            emitters.remove(runId);
            AgentChannelListener listener = listeners.remove(runId);
            if (listener != null) {
                try {
                    listenerContainer.removeMessageListener(listener);
                } catch (Exception e) {
                    log.warn("移除 Agent 事件监听失败，runId={}", runId);
                }
            }
        }
    }

    private void dispatchLocal(Long runId, AgentRunEventVO event) {
        List<SseEmitter> bucket = emitters.get(runId);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : bucket) {
            sendQuietly(emitter, event);
        }
    }

    private void sendQuietly(SseEmitter emitter, AgentRunEventVO event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(StrUtil.blankToDefault(event.getEvent(), "message"))
                    .data(event));
        } catch (IOException | IllegalStateException e) {
            // 连接已断开，忽略；SSE 断开后前端重新拉取 run 快照再重连
        }
    }

    public static String channel(Long runId) {
        return AgentConstant.RUN_EVENT_CHANNEL_PREFIX + runId;
    }

    /**
     * Redis 订阅回调：来自其他节点的事件投递给本机 SSE 连接
     */
    private class AgentChannelListener implements org.springframework.data.redis.connection.MessageListener {

        private final Long runId;

        AgentChannelListener(Long runId) {
            this.runId = runId;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                AgentRunEventVO event = JSONUtil.toBean(body, AgentRunEventVO.class);
                dispatchLocal(runId, event);
            } catch (Exception e) {
                log.warn("解析 Agent 事件失败，runId={}", runId, e);
            }
        }
    }
}

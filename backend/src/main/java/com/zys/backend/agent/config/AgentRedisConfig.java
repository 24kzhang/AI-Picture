package com.zys.backend.agent.config;

import com.zys.backend.agent.service.AgentQueueService;
import com.zys.backend.constant.AgentConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * Agent 专用 Redis 基础设施：
 * 1. Pub/Sub 监听容器（SSE 实时事件）
 * 2. Redis Streams 监听容器（工具任务队列，手动 ack）
 */
@Slf4j
@Configuration
@EnableScheduling
public class AgentRedisConfig {

    @Bean("agentRedisListenerContainer")
    public RedisMessageListenerContainer agentRedisListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> agentToolRunStreamContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate stringRedisTemplate,
            AgentQueueService queueService) {
        boolean streamsAvailable = ensureConsumerGroup(stringRedisTemplate);
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        if (streamsAvailable) {
            container.receive(queueService.consumer(),
                    StreamOffset.create(AgentConstant.TOOL_RUN_STREAM_KEY, ReadOffset.lastConsumed()),
                    queueService::onMessage);
            container.start();
            queueService.setStreamsAvailable(true);
            log.info("Agent 工具任务 Stream 消费者已启动：stream={}，group={}",
                    AgentConstant.TOOL_RUN_STREAM_KEY, AgentConstant.TOOL_RUN_CONSUMER_GROUP);
        } else {
            log.warn("Redis 不支持 Streams；Agent 工具任务改用本地异步执行");
        }
        return container;
    }

    private boolean ensureConsumerGroup(StringRedisTemplate stringRedisTemplate) {
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(AgentConstant.TOOL_RUN_STREAM_KEY, AgentConstant.TOOL_RUN_CONSUMER_GROUP);
            return true;
        } catch (Exception e) {
            // 已存在的消费组可正常继续消费；旧版 Redis 不支持 Streams。
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            log.warn("工具任务消费组不可用：{}", e.getMessage());
            return false;
        }
    }
}

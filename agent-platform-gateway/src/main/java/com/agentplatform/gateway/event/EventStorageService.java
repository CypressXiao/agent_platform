package com.agentplatform.gateway.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 事件存储服务
 * 异步存储 ToolCallEvent 到 Redis，支持按 runId 查询回放
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventStorageService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String EVENT_KEY_PREFIX = "event:run:";
    private static final Duration EVENT_TTL = Duration.ofDays(7);

    /**
     * 异步监听并存储事件
     */
    @Async
    @EventListener
    public void handleToolCallEvent(ToolCallEvent event) {
        try {
            String key = EVENT_KEY_PREFIX + event.getRunId();
            String eventJson = objectMapper.writeValueAsString(event);

            // 使用 Redis List 存储同一 runId 下的所有事件
            redisTemplate.opsForList().rightPush(key, eventJson);
            redisTemplate.expire(key, EVENT_TTL);

            log.debug("Stored event: runId={}, stepId={}", event.getRunId(), event.getStepId());

        } catch (Exception e) {
            log.error("Failed to store event: runId={}, error={}", event.getRunId(), e.getMessage());
        }
    }

    /**
     * 按 runId 查询所有事件（用于回放）
     */
    public List<ToolCallEvent> getEventsByRunId(String runId) {
        String key = EVENT_KEY_PREFIX + runId;
        List<String> eventJsonList = redisTemplate.opsForList().range(key, 0, -1);

        if (eventJsonList == null || eventJsonList.isEmpty()) {
            return List.of();
        }

        return eventJsonList.stream()
            .map(json -> {
                try {
                    return objectMapper.readValue(json, ToolCallEvent.class);
                } catch (Exception e) {
                    log.warn("Failed to parse event JSON: {}", e.getMessage());
                    return null;
                }
            })
            .filter(event -> event != null)
            .collect(Collectors.toList());
    }

    /**
     * 按 runId 获取事件数量
     */
    public long getEventCount(String runId) {
        String key = EVENT_KEY_PREFIX + runId;
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    /**
     * 删除指定 runId 的所有事件
     */
    public void deleteEventsByRunId(String runId) {
        String key = EVENT_KEY_PREFIX + runId;
        redisTemplate.delete(key);
        log.debug("Deleted events for runId={}", runId);
    }
}

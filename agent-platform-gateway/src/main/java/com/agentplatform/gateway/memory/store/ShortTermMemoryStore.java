package com.agentplatform.gateway.memory.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based short-term memory store.
 * Stores conversation context and working memory with automatic TTL expiration.
 * Key format: stm:{tenantId}:{agentId}:{namespace}
 */
@Component
@ConditionalOnProperty(name = "agent-platform.memory.enabled", havingValue = "true")
@Slf4j
public class ShortTermMemoryStore {

    private static final String KEY_PREFIX = "stm:";
    private static final long DEFAULT_TTL_SECONDS = 3600; // 1 hour
    private static final int MAX_ENTRIES_PER_KEY = 100;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ShortTermMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Save a short-term memory entry. Appends to a Redis list with TTL.
     */
    public Map<String, Object> save(String tenantId, String agentId, String namespace,
                                     String content, Map<String, Object> metadata, long ttlSeconds) {
        String key = buildKey(tenantId, agentId, namespace);
        String entryId = UUID.randomUUID().toString();

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", entryId);
        entry.put("content", content);
        entry.put("metadata", metadata != null ? metadata : Map.of());
        entry.put("timestamp", System.currentTimeMillis());

        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForList().rightPush(key, json);

            // Trim to max entries (keep latest)
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > MAX_ENTRIES_PER_KEY) {
                redisTemplate.opsForList().trim(key, size - MAX_ENTRIES_PER_KEY, -1);
            }

            // Set/refresh TTL
            long ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
            redisTemplate.expire(key, ttl, TimeUnit.SECONDS);

            log.debug("Saved short-term memory: key={}, entryId={}", key, entryId);
            return entry;
        } catch (Exception e) {
            log.error("Failed to save short-term memory: {}", e.getMessage(), e);
            throw new RuntimeException("Short-term memory save failed", e);
        }
    }

    /**
     * Query recent short-term memories (most recent first).
     */
    public List<Map<String, Object>> queryRecent(String tenantId, String agentId,
                                                   String namespace, int limit) {
        String key = buildKey(tenantId, agentId, namespace);

        try {
            Long size = redisTemplate.opsForList().size(key);
            if (size == null || size == 0) {
                return List.of();
            }

            long start = Math.max(0, size - limit);
            List<String> jsonEntries = redisTemplate.opsForList().range(key, start, -1);
            if (jsonEntries == null || jsonEntries.isEmpty()) {
                return List.of();
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = jsonEntries.size() - 1; i >= 0; i--) {
                Map<String, Object> entry = objectMapper.readValue(
                    jsonEntries.get(i), new TypeReference<>() {});
                results.add(entry);
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to query short-term memory: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Clear all short-term memories for a specific agent+namespace.
     */
    public void clear(String tenantId, String agentId, String namespace) {
        String key = buildKey(tenantId, agentId, namespace);
        redisTemplate.delete(key);
        log.debug("Cleared short-term memory: key={}", key);
    }

    /**
     * Get count of entries in a short-term memory key.
     */
    public long count(String tenantId, String agentId, String namespace) {
        String key = buildKey(tenantId, agentId, namespace);
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    private String buildKey(String tenantId, String agentId, String namespace) {
        return KEY_PREFIX + tenantId + ":" + agentId + ":" + (namespace != null ? namespace : "default");
    }
}

package com.agentplatform.gateway.authz;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.Grant;
import com.agentplatform.common.repository.GrantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class GrantEngine implements MessageListener {

    private final GrantRepository grantRepo;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final String REVOCATION_CHANNEL = "grant:revoked";

    @PostConstruct
    public void subscribeToRevocationEvents() {
        try {
            redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(REVOCATION_CHANNEL));
            log.info("Subscribed to Redis grant revocation channel: {}", REVOCATION_CHANNEL);
        } catch (Exception e) {
            log.warn("Failed to subscribe to grant revocation channel: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String grantId = new String(message.getBody());
        log.info("Received grant revocation event, invalidating cache for grantId: {}", grantId);
        invalidateCacheForGrant(grantId);
    }

    private void invalidateCacheForGrant(String grantId) {
        try {
            // 查找被吊销 grant 对应的缓存 key 并删除
            Grant grant = grantRepo.findById(grantId).orElse(null);
            if (grant != null && grant.getTools() != null) {
                for (String toolId : grant.getTools()) {
                    String cacheKey = "grant:%s:%s:%s".formatted(grant.getGrantorTid(), grant.getGranteeTid(), toolId);
                    redisTemplate.delete(cacheKey);
                }
            }
            // 同时尝试用通配符清理（兜底）
            Set<String> keys = redisTemplate.keys("grant:" + "*:" + grantId.substring(0, Math.min(8, grantId.length())) + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Failed to invalidate cache for grant {}: {}", grantId, e.getMessage());
        }
    }

    /**
     * Check cross-tenant authorization. Returns null for same-tenant access.
     */
    public Grant check(String actorTid, String ownerTid, String toolId) {
        if (actorTid.equals(ownerTid) || "system".equals(ownerTid)) {
            return null;
        }

        // L1: Redis cache
        String cacheKey = "grant:%s:%s:%s".formatted(ownerTid, actorTid, toolId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                Grant grant = objectMapper.readValue(cached, Grant.class);
                return validateGrant(grant);
            }
        } catch (Exception e) {
            log.debug("Redis cache miss or error for grant check: {}", e.getMessage());
        }

        // L2: Database query
        Grant grant = grantRepo.findActiveGrant(ownerTid, actorTid, toolId)
            .orElseThrow(() -> new McpException(McpErrorCode.FORBIDDEN_NO_GRANT,
                "No active grant from %s to %s for tool %s".formatted(ownerTid, actorTid, toolId)));

        // Write to cache
        try {
            String serialized = objectMapper.writeValueAsString(grant);
            redisTemplate.opsForValue().set(cacheKey, serialized, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache grant: {}", e.getMessage());
        }

        return validateGrant(grant);
    }

    private Grant validateGrant(Grant grant) {
        if (!"active".equals(grant.getStatus())) {
            throw new McpException(McpErrorCode.GRANT_REVOKED);
        }
        if (grant.getExpiresAt() != null && grant.getExpiresAt().isBefore(Instant.now())) {
            throw new McpException(McpErrorCode.GRANT_EXPIRED);
        }
        return grant;
    }

    @Transactional
    public void revoke(String grantId, String reason) {
        grantRepo.revoke(grantId, reason, Instant.now());
        // Immediately invalidate local cache
        invalidateCacheForGrant(grantId);
        // Broadcast cache invalidation to other instances
        try {
            redisTemplate.convertAndSend(REVOCATION_CHANNEL, grantId);
        } catch (Exception e) {
            log.warn("Failed to broadcast grant revocation: {}", e.getMessage());
        }
        eventPublisher.publishEvent(new GrantRevokedEvent(grantId));
    }

    public record GrantRevokedEvent(String grantId) {}
}

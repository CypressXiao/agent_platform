package com.agentplatform.gateway.authz;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.Grant;
import com.agentplatform.common.repository.GrantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class GrantEngine {

    private final GrantRepository grantRepo;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

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
        // Broadcast cache invalidation
        try {
            redisTemplate.convertAndSend("grant:revoked", grantId);
        } catch (Exception e) {
            log.warn("Failed to broadcast grant revocation: {}", e.getMessage());
        }
        eventPublisher.publishEvent(new GrantRevokedEvent(grantId));
    }

    public record GrantRevokedEvent(String grantId) {}
}

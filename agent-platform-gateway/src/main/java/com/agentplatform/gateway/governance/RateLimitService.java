package com.agentplatform.gateway.governance;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis-based sliding window rate limiter.
 * Key format: rate_limit:{tenantId}:{toolName}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    private static final String SLIDING_WINDOW_SCRIPT = """
        local key = KEYS[1]
        local now = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        
        -- Remove expired entries
        redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
        
        -- Count current entries
        local count = redis.call('ZCARD', key)
        
        if count < limit then
            -- Add new entry
            redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))
            redis.call('PEXPIRE', key, window)
            return 1
        else
            return 0
        end
        """;

    private final DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>(SLIDING_WINDOW_SCRIPT, Long.class);

    /**
     * Check rate limit. Throws RATE_LIMITED if exceeded.
     *
     * @param tenantId  Tenant ID
     * @param toolName  Tool name
     * @param limit     Max requests per window
     * @param windowMs  Window duration in milliseconds
     */
    public void check(String tenantId, String toolName, int limit, long windowMs) {
        if (limit <= 0) {
            return; // No limit configured
        }

        String key = "rate_limit:%s:%s".formatted(tenantId, toolName);
        long now = System.currentTimeMillis();

        try {
            Long result = redisTemplate.execute(rateLimitScript,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(limit));

            if (result == null || result == 0L) {
                log.warn("Rate limit exceeded for tenant {} tool {}", tenantId, toolName);
                throw new McpException(McpErrorCode.RATE_LIMITED,
                    "Rate limit exceeded: %d requests per %d ms for tool %s"
                        .formatted(limit, windowMs, toolName));
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Rate limit check failed (allowing request): {}", e.getMessage());
            // Fail open — allow request if Redis is unavailable
        }
    }
}

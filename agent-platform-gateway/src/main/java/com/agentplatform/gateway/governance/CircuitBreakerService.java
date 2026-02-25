package com.agentplatform.gateway.governance;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-upstream circuit breaker using Resilience4j.
 * Key: upstream server ID.
 */
@Service
@Slf4j
public class CircuitBreakerService {

    private final CircuitBreakerRegistry registry;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerService() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slowCallRateThreshold(80)
            .slowCallDurationThreshold(Duration.ofSeconds(5))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        this.registry = CircuitBreakerRegistry.of(defaultConfig);
    }

    /**
     * Check if the circuit is open for the given source. Throws CIRCUIT_OPEN if so.
     */
    public void checkState(String sourceId) {
        CircuitBreaker cb = getOrCreate(sourceId);
        if (cb.getState() == CircuitBreaker.State.OPEN) {
            log.warn("Circuit breaker OPEN for source: {}", sourceId);
            throw new McpException(McpErrorCode.CIRCUIT_OPEN,
                "Circuit breaker is open for upstream: " + sourceId);
        }
    }

    /**
     * Record a successful call.
     */
    public void recordSuccess(String sourceId) {
        getOrCreate(sourceId).onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Record a failed call.
     */
    public void recordFailure(String sourceId) {
        getOrCreate(sourceId).onError(0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new RuntimeException("upstream failure"));
    }

    private CircuitBreaker getOrCreate(String sourceId) {
        return breakers.computeIfAbsent(sourceId, id -> registry.circuitBreaker(id));
    }
}

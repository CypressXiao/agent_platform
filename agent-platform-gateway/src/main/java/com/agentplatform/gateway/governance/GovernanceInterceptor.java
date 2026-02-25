package com.agentplatform.gateway.governance;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Governance interceptor that applies rate limiting and circuit breaking
 * before and after tool calls.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GovernanceInterceptor {

    private final RateLimitService rateLimitService;
    private final CircuitBreakerService circuitBreakerService;

    private static final long DEFAULT_WINDOW_MS = 60_000L;

    /**
     * Pre-check: rate limit + circuit breaker state.
     */
    public void preCheck(CallerIdentity identity, Tool tool) {
        // Rate limit check
        int limit = tool.getRateLimit() != null ? tool.getRateLimit() : 100;
        rateLimitService.check(identity.getTenantId(), tool.getToolName(), limit, DEFAULT_WINDOW_MS);

        // Circuit breaker check (only for upstream tools)
        if (!"builtin".equals(tool.getSourceType())) {
            circuitBreakerService.checkState(tool.getSourceId());
        }
    }

    /**
     * Post-check: record success/failure for circuit breaker.
     */
    public void postCheck(CallerIdentity identity, Tool tool, boolean success) {
        if (!"builtin".equals(tool.getSourceType())) {
            if (success) {
                circuitBreakerService.recordSuccess(tool.getSourceId());
            } else {
                circuitBreakerService.recordFailure(tool.getSourceId());
            }
        }
    }
}

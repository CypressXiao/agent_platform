package com.agentplatform.gateway.governance;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 统一弹性服务
 * 集中管理重试、超时、隔离、熔断策略
 */
@Service
@Slf4j
public class ResilienceService {

    private final ConcurrentHashMap<String, ResilienceConfig> configCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimeLimiter> timeLimiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

    private final CircuitBreakerRegistry cbRegistry;
    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry tlRegistry;
    private final BulkheadRegistry bhRegistry;
    private final ExecutorService executor;

    public ResilienceService() {
        this.cbRegistry = CircuitBreakerRegistry.ofDefaults();
        this.retryRegistry = RetryRegistry.ofDefaults();
        this.tlRegistry = TimeLimiterRegistry.ofDefaults();
        this.bhRegistry = BulkheadRegistry.ofDefaults();
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * 注册配置
     */
    public void registerConfig(String configId, ResilienceConfig config) {
        configCache.put(configId, config);
        // 清除旧的组件缓存
        circuitBreakers.remove(configId);
        retries.remove(configId);
        timeLimiters.remove(configId);
        bulkheads.remove(configId);
        log.info("Registered resilience config: {}", configId);
    }

    /**
     * 获取配置
     */
    public ResilienceConfig getConfig(String configId) {
        return configCache.getOrDefault(configId, ResilienceConfig.defaultConfig());
    }

    /**
     * 带弹性保护的执行
     */
    public <T> T executeWithResilience(String configId, Supplier<T> supplier) {
        ResilienceConfig config = getConfig(configId);

        // 获取或创建组件
        CircuitBreaker cb = getOrCreateCircuitBreaker(configId, config);
        Retry retry = getOrCreateRetry(configId, config);
        TimeLimiter tl = getOrCreateTimeLimiter(configId, config);
        Bulkhead bh = getOrCreateBulkhead(configId, config);

        // 组合装饰器
        Supplier<CompletableFuture<T>> futureSupplier = () -> 
            CompletableFuture.supplyAsync(supplier, executor);

        Supplier<CompletableFuture<T>> decoratedSupplier = 
            Bulkhead.decorateSupplier(bh,
                CircuitBreaker.decorateSupplier(cb,
                    Retry.decorateSupplier(retry, futureSupplier)));

        try {
            CompletableFuture<T> future = decoratedSupplier.get();
            return tl.executeFutureSupplier(() -> future);
        } catch (Exception e) {
            log.error("Resilience execution failed for {}: {}", configId, e.getMessage());
            throw new RuntimeException("Execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * 带弹性保护的同步执行（简化版）
     */
    public <T> T executeSync(String configId, Supplier<T> supplier) {
        ResilienceConfig config = getConfig(configId);
        CircuitBreaker cb = getOrCreateCircuitBreaker(configId, config);
        Retry retry = getOrCreateRetry(configId, config);

        Supplier<T> decoratedSupplier = 
            CircuitBreaker.decorateSupplier(cb,
                Retry.decorateSupplier(retry, supplier));

        return decoratedSupplier.get();
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String configId, long durationMs) {
        CircuitBreaker cb = circuitBreakers.get(configId);
        if (cb != null) {
            cb.onSuccess(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 记录失败
     */
    public void recordFailure(String configId, Throwable throwable) {
        CircuitBreaker cb = circuitBreakers.get(configId);
        if (cb != null) {
            cb.onError(0, TimeUnit.MILLISECONDS, throwable);
        }
    }

    private CircuitBreaker getOrCreateCircuitBreaker(String configId, ResilienceConfig config) {
        return circuitBreakers.computeIfAbsent(configId, id -> {
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .slowCallDurationThreshold(config.getSlowCallDurationThreshold())
                .waitDurationInOpenState(config.getWaitDurationInOpenState())
                .slidingWindowSize(config.getSlidingWindowSize())
                .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                .build();
            return cbRegistry.circuitBreaker(id, cbConfig);
        });
    }

    private Retry getOrCreateRetry(String configId, ResilienceConfig config) {
        return retries.computeIfAbsent(configId, id -> {
            RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(config.getMaxRetries())
                .waitDuration(config.getRetryInterval())
                .retryOnException(e -> isRetryable(e, config))
                .build();
            return retryRegistry.retry(id, retryConfig);
        });
    }

    private TimeLimiter getOrCreateTimeLimiter(String configId, ResilienceConfig config) {
        return timeLimiters.computeIfAbsent(configId, id -> {
            TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(config.getTimeout())
                .cancelRunningFuture(true)
                .build();
            return tlRegistry.timeLimiter(id, tlConfig);
        });
    }

    private Bulkhead getOrCreateBulkhead(String configId, ResilienceConfig config) {
        return bulkheads.computeIfAbsent(configId, id -> {
            BulkheadConfig bhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(config.getMaxConcurrentCalls())
                .maxWaitDuration(Duration.ofMillis(config.getMaxWaitDuration()))
                .build();
            return bhRegistry.bulkhead(id, bhConfig);
        });
    }

    private boolean isRetryable(Throwable e, ResilienceConfig config) {
        String exceptionName = e.getClass().getName();
        for (String retryable : config.getRetryableExceptions()) {
            if (exceptionName.equals(retryable) || exceptionName.endsWith("." + retryable)) {
                return true;
            }
        }
        return false;
    }
}

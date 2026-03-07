package com.agentplatform.gateway.governance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * 统一的弹性配置
 * 支持按租户/工具/上游配置重试、超时、隔离策略
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResilienceConfig {

    /**
     * 配置 ID（租户ID:工具名 或 租户ID:上游ID）
     */
    private String configId;

    /**
     * 租户 ID
     */
    private String tenantId;

    /**
     * 目标类型：tool / upstream / llm
     */
    @Builder.Default
    private TargetType targetType = TargetType.TOOL;

    /**
     * 目标名称（工具名或上游 ID）
     */
    private String targetName;

    // ========== 超时配置 ==========

    /**
     * 调用超时时间
     */
    @Builder.Default
    private Duration timeout = Duration.ofSeconds(30);

    // ========== 重试配置 ==========

    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxRetries = 3;

    /**
     * 重试间隔
     */
    @Builder.Default
    private Duration retryInterval = Duration.ofMillis(500);

    /**
     * 重试间隔倍数（指数退避）
     */
    @Builder.Default
    private double retryMultiplier = 2.0;

    /**
     * 最大重试间隔
     */
    @Builder.Default
    private Duration maxRetryInterval = Duration.ofSeconds(10);

    /**
     * 可重试的异常类型
     */
    @Builder.Default
    private String[] retryableExceptions = {"java.net.SocketTimeoutException", "java.io.IOException"};

    // ========== 隔离配置（Bulkhead） ==========

    /**
     * 最大并发调用数
     */
    @Builder.Default
    private int maxConcurrentCalls = 25;

    /**
     * 等待队列大小
     */
    @Builder.Default
    private int maxWaitDuration = 0;

    // ========== 熔断配置 ==========

    /**
     * 失败率阈值（百分比）
     */
    @Builder.Default
    private int failureRateThreshold = 50;

    /**
     * 慢调用率阈值（百分比）
     */
    @Builder.Default
    private int slowCallRateThreshold = 80;

    /**
     * 慢调用时间阈值
     */
    @Builder.Default
    private Duration slowCallDurationThreshold = Duration.ofSeconds(5);

    /**
     * 熔断器打开后等待时间
     */
    @Builder.Default
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    /**
     * 滑动窗口大小
     */
    @Builder.Default
    private int slidingWindowSize = 10;

    /**
     * 最小调用次数
     */
    @Builder.Default
    private int minimumNumberOfCalls = 5;

    public enum TargetType {
        TOOL,
        UPSTREAM,
        LLM
    }

    /**
     * 默认配置
     */
    public static ResilienceConfig defaultConfig() {
        return ResilienceConfig.builder().build();
    }

    /**
     * LLM 调用配置（更长超时）
     */
    public static ResilienceConfig forLlm() {
        return ResilienceConfig.builder()
            .targetType(TargetType.LLM)
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)
            .retryInterval(Duration.ofSeconds(1))
            .slowCallDurationThreshold(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 上游服务配置
     */
    public static ResilienceConfig forUpstream() {
        return ResilienceConfig.builder()
            .targetType(TargetType.UPSTREAM)
            .timeout(Duration.ofSeconds(30))
            .maxRetries(3)
            .retryInterval(Duration.ofMillis(500))
            .build();
    }
}

package com.agentplatform.gateway.event;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 统一的工具调用事件模型
 * 用于观测、评测和回放
 */
@Data
@Builder
public class ToolCallEvent {

    /**
     * 运行 ID（一次完整的 Agent 执行）
     */
    private String runId;

    /**
     * 步骤 ID（当前调用的唯一标识）
     */
    private String stepId;

    /**
     * 父步骤 ID（用于构建调用树）
     */
    private String parentStepId;

    /**
     * 租户 ID
     */
    private String tenantId;

    /**
     * 调用者 ID
     */
    private String callerId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具所有者租户 ID
     */
    private String toolOwnerTid;

    /**
     * 工具来源类型（builtin/upstream_mcp/upstream_rest）
     */
    private String sourceType;

    /**
     * 调用参数
     */
    private Map<String, Object> arguments;

    /**
     * 调用结果
     */
    private Object result;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    /**
     * 错误码（如果失败）
     */
    private String errorCode;

    /**
     * 延迟（毫秒）
     */
    private long latencyMs;

    /**
     * 状态：SUCCESS / ERROR / TIMEOUT
     */
    private EventStatus status;

    /**
     * 事件时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Trace ID（用于分布式追踪关联）
     */
    private String traceId;

    /**
     * Span ID
     */
    private String spanId;

    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;

    public enum EventStatus {
        SUCCESS,
        ERROR,
        TIMEOUT
    }
}

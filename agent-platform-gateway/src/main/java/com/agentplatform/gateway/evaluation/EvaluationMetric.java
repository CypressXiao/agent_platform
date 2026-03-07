package com.agentplatform.gateway.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 评测指标
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationMetric {

    /**
     * 指标 ID
     */
    private String metricId;

    /**
     * 评测类型：rag / llm / tool
     */
    private EvaluationType type;

    /**
     * 租户 ID
     */
    private String tenantId;

    /**
     * 关联的 runId
     */
    private String runId;

    /**
     * 指标名称
     */
    private String name;

    /**
     * 指标值（0-1）
     */
    private Double value;

    /**
     * 评测时间
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;

    public enum EvaluationType {
        RAG,        // RAG 检索质量
        LLM,        // LLM 响应质量
        TOOL,       // 工具调用质量
        E2E         // 端到端质量
    }
}

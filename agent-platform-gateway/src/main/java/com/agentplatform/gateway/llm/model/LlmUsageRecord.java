package com.agentplatform.gateway.llm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "llm_usage_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageRecord {

    @Id
    @Column(name = "record_id")
    private String recordId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "prompt_tokens")
    @Builder.Default
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens")
    @Builder.Default
    private Integer completionTokens = 0;

    @Column(name = "total_tokens")
    @Builder.Default
    private Integer totalTokens = 0;

    @Column(name = "cost")
    private BigDecimal cost;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

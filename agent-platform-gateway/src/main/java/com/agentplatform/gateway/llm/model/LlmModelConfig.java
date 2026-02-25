package com.agentplatform.gateway.llm.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "llm_model_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelConfig {

    @Id
    @Column(name = "model_id")
    private String modelId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "max_tokens", nullable = false)
    @Builder.Default
    private Integer maxTokens = 4096;

    @Column(name = "input_price_per_m_token")
    private BigDecimal inputPricePerMToken;

    @Column(name = "output_price_per_m_token")
    private BigDecimal outputPricePerMToken;

    @Column(name = "supports_streaming")
    @Builder.Default
    private Boolean supportsStreaming = true;

    @Column(name = "supports_tools")
    @Builder.Default
    private Boolean supportsTools = true;

    @Column(name = "fallback_model_id")
    private String fallbackModelId;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

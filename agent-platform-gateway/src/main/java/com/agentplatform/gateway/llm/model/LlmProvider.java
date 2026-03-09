package com.agentplatform.gateway.llm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "llm_provider")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProvider {

    @Id
    @Column(name = "provider_id")
    private String providerId;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "api_key_ref")
    private String apiKeyRef;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

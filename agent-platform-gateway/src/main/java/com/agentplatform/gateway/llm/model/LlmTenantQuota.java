package com.agentplatform.gateway.llm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "llm_tenant_quota")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmTenantQuota {

    @Id
    @Column(name = "quota_id")
    private String quotaId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "model_id", nullable = false)
    @Builder.Default
    private String modelId = "*";

    @Column(name = "rpm_limit")
    @Builder.Default
    private Integer rpmLimit = 60;

    @Column(name = "tpm_limit")
    @Builder.Default
    private Integer tpmLimit = 100000;

    @Column(name = "monthly_token_budget")
    @Builder.Default
    private Long monthlyTokenBudget = 10000000L;

    @Column(name = "current_month_usage")
    @Builder.Default
    private Long currentMonthUsage = 0L;

    @Column(name = "reset_at")
    private Instant resetAt;
}

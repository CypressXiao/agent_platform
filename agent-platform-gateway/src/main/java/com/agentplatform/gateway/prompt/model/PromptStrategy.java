package com.agentplatform.gateway.prompt.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

/**
 * Prompt 策略配置
 * 支持按租户/任务/模型的策略路由和 A/B 测试
 */
@Entity
@Table(name = "prompt_strategy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptStrategy {

    @Id
    @Column(name = "strategy_id", length = 64)
    private String strategyId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * 匹配条件（JSON）
     * 例如：{"task_type": "qa", "model": "gpt-4", "tags": ["production"]}
     */
    @Type(JsonType.class)
    @Column(name = "match_conditions", columnDefinition = "json")
    private Map<String, Object> matchConditions;

    /**
     * 流量分配规则（JSON）
     * 例如：[{"template": "qa_v1", "version": 1, "weight": 70}, {"template": "qa_v2", "version": 1, "weight": 30}]
     */
    @Type(JsonType.class)
    @Column(name = "traffic_rules", columnDefinition = "json")
    private java.util.List<TrafficRule> trafficRules;

    @Column(name = "status", length = 32)
    @Builder.Default
    private String status = "active";

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * 流量分配规则
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrafficRule {
        private String templateName;
        private Integer version;
        private Integer weight;
        private Map<String, Object> metadata;
    }
}

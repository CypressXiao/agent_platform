package com.agentplatform.gateway.planner.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "plan")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    @Column(name = "plan_id")
    private String planId;

    @Column(name = "actor_tid", nullable = false)
    private String actorTid;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(name = "trace_id")
    private String traceId;

    @Type(JsonType.class)
    @Column(name = "steps", columnDefinition = "json")
    private List<Map<String, Object>> steps;

    @Type(JsonType.class)
    @Column(name = "context", columnDefinition = "json")
    private Map<String, Object> context;

    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    @Column(name = "llm_model")
    private String llmModel;

    @Column(name = "llm_tokens_used")
    private Integer llmTokensUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}

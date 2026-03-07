package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "tool")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tool {

    @Id
    @Column(name = "tool_id")
    private String toolId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "source_type", nullable = false)
    private String sourceType; // builtin | upstream_mcp | upstream_rest

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Type(JsonType.class)
    @Column(name = "required_scopes", columnDefinition = "json")
    private List<String> requiredScopes;

    @Type(JsonType.class)
    @Column(name = "input_schema", columnDefinition = "json")
    private Map<String, Object> inputSchema;

    @Type(JsonType.class)
    @Column(name = "execution_mapping", columnDefinition = "json")
    private Map<String, Object> executionMapping;

    @Type(JsonType.class)
    @Column(name = "response_mapping", columnDefinition = "json")
    private Map<String, Object> responseMapping;

    @Column(name = "priority")
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "idempotent")
    @Builder.Default
    private Boolean idempotent = false;

    @Column(name = "timeout_ms")
    @Builder.Default
    private Integer timeoutMs = 30000;

    @Column(name = "rate_limit")
    @Builder.Default
    private Integer rateLimit = 100;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "execution_mode")
    @Builder.Default
    private String executionMode = "SYNC"; // SYNC | ASYNC

    @Column(name = "result_delivery")
    private String resultDelivery; // CALLBACK | QUEUE (only for ASYNC)

    @Column(name = "callback_url")
    private String callbackUrl;

    @Type(JsonType.class)
    @Column(name = "callback_auth", columnDefinition = "json")
    private Map<String, Object> callbackAuth; // { type: API_KEY|HMAC|OAUTH2, ... }

    @Column(name = "queue_topic")
    private String queueTopic;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // Transient fields for shared tool display
    @Transient
    private String sharedFrom;

    @Transient
    @Builder.Default
    private Boolean requiresGrant = false;
}

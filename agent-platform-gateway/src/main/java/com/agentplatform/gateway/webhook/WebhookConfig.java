package com.agentplatform.gateway.webhook;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Webhook 配置
 * 支持工具变更、审计事件、配额预警等事件的 Webhook 推送
 */
@Entity
@Table(name = "webhook_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookConfig {

    @Id
    @Column(name = "webhook_id", length = 64)
    private String webhookId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * Webhook URL
     */
    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    /**
     * 订阅的事件类型
     */
    @Type(JsonType.class)
    @Column(name = "event_types", columnDefinition = "json")
    private List<String> eventTypes;

    /**
     * 请求头（如认证信息）
     */
    @Type(JsonType.class)
    @Column(name = "headers", columnDefinition = "json")
    private Map<String, String> headers;

    /**
     * 密钥（用于签名验证）
     */
    @Column(name = "secret", length = 256)
    private String secret;

    /**
     * 状态：active / disabled
     */
    @Column(name = "status", length = 32)
    @Builder.Default
    private String status = "active";

    /**
     * 重试配置
     */
    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "retry_interval_ms")
    @Builder.Default
    private Long retryIntervalMs = 1000L;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * 支持的事件类型
     */
    public enum EventType {
        TOOL_CREATED,
        TOOL_UPDATED,
        TOOL_DELETED,
        TOOL_CALL_SUCCESS,
        TOOL_CALL_FAILURE,
        QUOTA_WARNING,
        QUOTA_EXCEEDED,
        UPSTREAM_UNHEALTHY,
        UPSTREAM_RECOVERED,
        AUDIT_EVENT
    }
}

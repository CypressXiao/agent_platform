package com.agentplatform.gateway.webhook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Webhook 事件载荷
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    /**
     * 事件 ID
     */
    private String eventId;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 租户 ID
     */
    private String tenantId;

    /**
     * 事件时间
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 事件数据
     */
    private Map<String, Object> data;

    /**
     * 关联的资源 ID
     */
    private String resourceId;

    /**
     * 关联的资源类型
     */
    private String resourceType;
}

package com.agentplatform.gateway.webhook;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.event.ToolCallEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Webhook 服务
 * 负责事件推送、重试、签名验证
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookRepository webhookRepo;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /**
     * 注册 Webhook
     */
    public WebhookConfig register(CallerIdentity identity, String name, String url,
                                   List<String> eventTypes, Map<String, String> headers,
                                   String secret) {
        WebhookConfig config = WebhookConfig.builder()
            .webhookId(UUID.randomUUID().toString())
            .tenantId(identity.getTenantId())
            .name(name)
            .url(url)
            .eventTypes(eventTypes)
            .headers(headers)
            .secret(secret)
            .status("active")
            .build();

        return webhookRepo.save(config);
    }

    /**
     * 列出租户的 Webhooks
     */
    public List<WebhookConfig> list(CallerIdentity identity) {
        return webhookRepo.findByTenantIdAndStatus(identity.getTenantId(), "active");
    }

    /**
     * 禁用 Webhook
     */
    public void disable(String webhookId) {
        WebhookConfig config = webhookRepo.findById(webhookId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + webhookId));
        config.setStatus("disabled");
        webhookRepo.save(config);
    }

    /**
     * 发送 Webhook 事件
     */
    @Async
    public void sendEvent(String tenantId, WebhookEvent event) {
        List<WebhookConfig> webhooks = webhookRepo.findByTenantIdAndStatus(tenantId, "active");

        for (WebhookConfig webhook : webhooks) {
            if (webhook.getEventTypes() != null && 
                webhook.getEventTypes().contains(event.getEventType())) {
                sendWithRetry(webhook, event);
            }
        }
    }

    /**
     * 监听 ToolCallEvent 并转发为 Webhook
     */
    @Async
    @EventListener
    public void handleToolCallEvent(ToolCallEvent event) {
        String eventType = event.getStatus() == ToolCallEvent.EventStatus.SUCCESS
            ? "TOOL_CALL_SUCCESS"
            : "TOOL_CALL_FAILURE";

        WebhookEvent webhookEvent = WebhookEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType(eventType)
            .tenantId(event.getTenantId())
            .timestamp(event.getTimestamp())
            .resourceId(event.getToolName())
            .resourceType("tool")
            .data(Map.of(
                "runId", event.getRunId(),
                "stepId", event.getStepId(),
                "toolName", event.getToolName(),
                "latencyMs", event.getLatencyMs(),
                "status", event.getStatus().name(),
                "errorCode", event.getErrorCode() != null ? event.getErrorCode() : "",
                "errorMessage", event.getErrorMessage() != null ? event.getErrorMessage() : ""
            ))
            .build();

        sendEvent(event.getTenantId(), webhookEvent);
    }

    /**
     * 发送工具变更事件
     */
    public void sendToolChangedEvent(String tenantId, String toolName, String action) {
        WebhookEvent event = WebhookEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("TOOL_" + action.toUpperCase())
            .tenantId(tenantId)
            .resourceId(toolName)
            .resourceType("tool")
            .data(Map.of("toolName", toolName, "action", action))
            .build();

        sendEvent(tenantId, event);
    }

    /**
     * 发送配额预警事件
     */
    public void sendQuotaWarningEvent(String tenantId, String quotaType, 
                                       double usagePercent, double threshold) {
        String eventType = usagePercent >= 100 ? "QUOTA_EXCEEDED" : "QUOTA_WARNING";

        WebhookEvent event = WebhookEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType(eventType)
            .tenantId(tenantId)
            .resourceType("quota")
            .data(Map.of(
                "quotaType", quotaType,
                "usagePercent", usagePercent,
                "threshold", threshold
            ))
            .build();

        sendEvent(tenantId, event);
    }

    /**
     * 发送上游健康状态变更事件
     */
    public void sendUpstreamHealthEvent(String tenantId, String serverId, 
                                         String status, String previousStatus) {
        String eventType = "healthy".equals(status) ? "UPSTREAM_RECOVERED" : "UPSTREAM_UNHEALTHY";

        WebhookEvent event = WebhookEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType(eventType)
            .tenantId(tenantId)
            .resourceId(serverId)
            .resourceType("upstream")
            .data(Map.of(
                "serverId", serverId,
                "status", status,
                "previousStatus", previousStatus
            ))
            .build();

        sendEvent(tenantId, event);
    }

    /**
     * 直接发送 WebhookEvent（用于 JobService 等场景）
     */
    public void sendWebhook(WebhookEvent event) {
        sendEvent(event.getTenantId(), event);
    }

    /**
     * 带重试的发送
     */
    private void sendWithRetry(WebhookConfig webhook, WebhookEvent event) {
        int maxRetries = webhook.getMaxRetries() != null ? webhook.getMaxRetries() : 3;
        long retryInterval = webhook.getRetryIntervalMs() != null ? webhook.getRetryIntervalMs() : 1000L;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                send(webhook, event);
                log.debug("Webhook sent successfully: webhookId={}, eventType={}", 
                    webhook.getWebhookId(), event.getEventType());
                return;
            } catch (Exception e) {
                log.warn("Webhook send failed (attempt {}/{}): webhookId={}, error={}", 
                    attempt + 1, maxRetries + 1, webhook.getWebhookId(), e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryInterval * (attempt + 1)); // 指数退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        log.error("Webhook send failed after {} retries: webhookId={}", 
            maxRetries + 1, webhook.getWebhookId());
    }

    /**
     * 发送单个 Webhook
     */
    private void send(WebhookConfig webhook, WebhookEvent event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Event", event.getEventType());
        headers.set("X-Webhook-Timestamp", Instant.now().toString());

        // 添加自定义头
        if (webhook.getHeaders() != null) {
            webhook.getHeaders().forEach(headers::set);
        }

        // 添加签名
        if (webhook.getSecret() != null && !webhook.getSecret().isEmpty()) {
            String signature = computeSignature(payload, webhook.getSecret());
            headers.set("X-Webhook-Signature", signature);
        }

        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        restTemplate.postForEntity(webhook.getUrl(), request, String.class);
    }

    /**
     * 计算 HMAC-SHA256 签名
     */
    private String computeSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to compute signature: {}", e.getMessage());
            return "";
        }
    }
}

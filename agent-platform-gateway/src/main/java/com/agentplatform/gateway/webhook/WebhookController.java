package com.agentplatform.gateway.webhook;

import com.agentplatform.common.model.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook 管理 API
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Webhook 配置与管理")
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    @Operation(summary = "注册 Webhook", description = "注册新的 Webhook 端点")
    public ResponseEntity<WebhookConfig> register(
            @RequestAttribute CallerIdentity identity,
            @RequestBody RegisterWebhookRequest request) {
        WebhookConfig config = webhookService.register(
            identity,
            request.getName(),
            request.getUrl(),
            request.getEventTypes(),
            request.getHeaders(),
            request.getSecret()
        );
        return ResponseEntity.ok(config);
    }

    @GetMapping
    @Operation(summary = "列出 Webhooks", description = "列出租户的所有 Webhook 配置")
    public ResponseEntity<List<WebhookConfig>> list(@RequestAttribute CallerIdentity identity) {
        return ResponseEntity.ok(webhookService.list(identity));
    }

    @DeleteMapping("/{webhookId}")
    @Operation(summary = "禁用 Webhook")
    public ResponseEntity<Void> disable(@PathVariable String webhookId) {
        webhookService.disable(webhookId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test/{webhookId}")
    @Operation(summary = "测试 Webhook", description = "发送测试事件到指定 Webhook")
    public ResponseEntity<Map<String, String>> test(
            @RequestAttribute CallerIdentity identity,
            @PathVariable String webhookId) {
        WebhookEvent testEvent = WebhookEvent.builder()
            .eventId("test-" + System.currentTimeMillis())
            .eventType("TEST")
            .tenantId(identity.getTenantId())
            .resourceType("test")
            .data(Map.of("message", "This is a test webhook event"))
            .build();

        webhookService.sendEvent(identity.getTenantId(), testEvent);
        return ResponseEntity.ok(Map.of("status", "sent", "eventId", testEvent.getEventId()));
    }

    @lombok.Data
    public static class RegisterWebhookRequest {
        private String name;
        private String url;
        private List<String> eventTypes;
        private Map<String, String> headers;
        private String secret;
    }
}

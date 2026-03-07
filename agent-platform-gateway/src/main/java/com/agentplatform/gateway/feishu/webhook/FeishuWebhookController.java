package com.agentplatform.gateway.feishu.webhook;

import com.agentplatform.gateway.feishu.config.FeishuConfig;
import com.agentplatform.gateway.feishu.model.FeishuSyncTask;
import com.agentplatform.gateway.feishu.service.FeishuSyncService;
import com.agentplatform.gateway.feishu.service.SyncTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/feishu/webhook")
@RequiredArgsConstructor
@Tag(name = "Feishu Webhook", description = "飞书事件订阅回调接口")
public class FeishuWebhookController {

    private final FeishuConfig feishuConfig;
    private final FeishuSyncService feishuSyncService;
    private final SyncTaskService syncTaskService;
    private final ObjectMapper objectMapper;

    @PostMapping("/event")
    @Operation(summary = "飞书事件回调", description = "接收飞书事件订阅的回调请求")
    public ResponseEntity<Object> handleEvent(
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        try {
            JsonNode body = objectMapper.readTree(rawBody);

            // 处理 URL 验证请求
            if (body.has("challenge")) {
                String challenge = body.get("challenge").asText();
                log.info("Received Feishu webhook URL verification challenge");
                return ResponseEntity.ok(Map.of("challenge", challenge));
            }

            // 验证签名（如果配置了 verification token）
            if (!verifySignature(timestamp, nonce, rawBody, signature)) {
                log.warn("Invalid webhook signature");
                return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
            }

            // 处理事件
            if (body.has("header") && body.has("event")) {
                return handleEventV2(body);
            } else if (body.has("event")) {
                return handleEventV1(body);
            }

            log.warn("Unknown webhook format: {}", rawBody);
            return ResponseEntity.ok(Map.of("status", "ignored"));

        } catch (Exception e) {
            log.error("Error processing webhook event", e);
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    private ResponseEntity<Object> handleEventV2(JsonNode body) {
        JsonNode header = body.get("header");
        JsonNode event = body.get("event");

        String eventType = header.has("event_type") ? header.get("event_type").asText() : "";
        String eventId = header.has("event_id") ? header.get("event_id").asText() : "";

        log.info("Received Feishu event v2: type={}, id={}", eventType, eventId);

        switch (eventType) {
            case "drive.file.deleted_v1":
            case "docx.document.deleted_v1":
                handleDocumentDeleted(event);
                break;
            case "drive.file.permission_member_added_v1":
            case "drive.file.permission_member_removed_v1":
                handlePermissionChange(event);
                break;
            default:
                log.debug("Ignoring event type: {}", eventType);
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private ResponseEntity<Object> handleEventV1(JsonNode body) {
        JsonNode event = body.get("event");
        String eventType = body.has("type") ? body.get("type").asText() : "";

        log.info("Received Feishu event v1: type={}", eventType);

        // V1 格式的事件处理 - 只处理删除事件
        if ("file_deleted".equals(eventType) || "doc_deleted".equals(eventType)) {
            handleDocumentDeleted(event);
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private void handleDocumentDeleted(JsonNode event) {
        String docToken = extractDocToken(event);
        if (docToken == null || docToken.isEmpty()) {
            log.warn("Cannot extract doc_token from delete event: {}", event);
            return;
        }

        log.info("Document deleted via webhook: {}", docToken);

        try {
            feishuSyncService.updateDocumentStatus(docToken, "DELETED", "Webhook delete event");
            // TODO: 触发向量库删除
            log.info("Marked document as deleted: {}", docToken);
        } catch (Exception e) {
            log.error("Failed to handle document deletion: {}", docToken, e);
        }
    }

    private void handlePermissionChange(JsonNode event) {
        String docToken = extractDocToken(event);
        if (docToken == null || docToken.isEmpty()) {
            return;
        }

        log.info("Permission changed via webhook for document: {}", docToken);

        // 权限变更后，尝试重新同步以验证权限
        try {
            if (feishuSyncService.checkDocumentNeedsSync(docToken)) {
                FeishuSyncTask task = syncTaskService.createTask(docToken, "WEBHOOK", "feishu-webhook");
                syncTaskService.executeTaskAsync(task.getTaskId());
            }
        } catch (Exception e) {
            log.warn("Permission check failed for document: {}", docToken, e);
        }
    }

    private String extractDocToken(JsonNode event) {
        // 尝试多种字段名
        String[] tokenFields = {"file_token", "doc_token", "token", "obj_token"};
        for (String field : tokenFields) {
            if (event.has(field)) {
                return event.get(field).asText();
            }
        }

        // 尝试从嵌套结构中提取
        if (event.has("file") && event.get("file").has("file_token")) {
            return event.get("file").get("file_token").asText();
        }
        if (event.has("document") && event.get("document").has("document_id")) {
            return event.get("document").get("document_id").asText();
        }

        return null;
    }

    private boolean verifySignature(String timestamp, String nonce, String body, String signature) {
        String verificationToken = feishuConfig.getAppSecret();
        if (verificationToken == null || verificationToken.isEmpty()) {
            // 未配置验证 token，跳过验证
            return true;
        }

        if (timestamp == null || nonce == null || signature == null) {
            // 缺少签名信息，跳过验证（可能是旧版本事件）
            return true;
        }

        try {
            String toSign = timestamp + nonce + verificationToken + body;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(toSign.getBytes(StandardCharsets.UTF_8));
            String calculatedSignature = bytesToHex(hash);
            return calculatedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @PostMapping("/card")
    @Operation(summary = "飞书卡片回调", description = "接收飞书消息卡片的交互回调")
    public ResponseEntity<Object> handleCardCallback(@RequestBody JsonNode body) {
        log.info("Received Feishu card callback: {}", body);

        // 处理卡片交互（如确认同步、取消等）
        if (body.has("action")) {
            JsonNode action = body.get("action");
            String actionValue = action.has("value") ? action.get("value").asText() : "";

            if (actionValue.startsWith("sync:")) {
                String docToken = actionValue.substring(5);
                try {
                    FeishuSyncTask task = syncTaskService.createTask(docToken, "MANUAL", "card-callback");
                    syncTaskService.executeTaskAsync(task.getTaskId());
                    return ResponseEntity.ok(Map.of(
                            "toast", Map.of("type", "success", "content", "同步任务已创建")
                    ));
                } catch (Exception e) {
                    return ResponseEntity.ok(Map.of(
                            "toast", Map.of("type", "error", "content", "创建同步任务失败: " + e.getMessage())
                    ));
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}

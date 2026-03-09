package com.agentplatform.gateway.llm;

import com.agentplatform.common.model.CallerIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM REST API Controller
 * 提供 HTTP 接口访问 LLM 功能
 */
@RestController
@RequestMapping("/api/v1/llm")
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LlmController {

    private final LlmRouterService llmRouterService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 专用线程池处理 SSE 请求
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-chat-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });

    /**
     * Chat completion API (SSE 流式响应) - 完善版本
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestAttribute CallerIdentity identity,
            @RequestParam String model,
            @RequestParam String messages,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens) {
        
        // 认证检查
        if (identity == null) {
            return createErrorEmitter("Authentication required", "AUTH_REQUIRED");
        }

        // 创建 SSE 发射器，设置超时时间为 5 分钟
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean isCompleted = new AtomicBoolean(false);
        
        // 设置完成回调 - 清理资源
        emitter.onCompletion(() -> {
            isCompleted.set(true);
            log.debug("SSE connection completed for tenant: {}", identity.getTenantId());
        });
        
        // 设置超时回调
        emitter.onTimeout(() -> {
            if (!isCompleted.get()) {
                log.warn("SSE connection timeout for tenant: {}", identity.getTenantId());
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of(
                            "error", "Connection timeout",
                            "code", "TIMEOUT"
                        )));
                } catch (IOException e) {
                    log.error("Failed to send timeout event", e);
                }
                emitter.complete();
            }
        });
        
        CompletableFuture<Map<String, Object>> responseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, Object>> messageList = parseMessages(messages);
                return llmRouterService.chat(
                    identity, model, messageList, temperature, maxTokens);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid request parameters: " + e.getMessage(), e);
            } catch (SecurityException e) {
                throw new RuntimeException("Access denied: " + e.getMessage(), e);
            }
        }, sseExecutor);

        // 处理正常响应
        responseFuture.thenAccept(response -> {
            if (isCompleted.get()) {
                return;
            }
            String content = (String) response.getOrDefault("content", "");
            if (content.isBlank()) {
                sendError(emitter, "Empty response from LLM", "EMPTY_RESPONSE");
                return;
            }
            sendChunk(emitter, content);

            Object usageObj = response.get("usage");
            Map<String, Object> usage = usageObj instanceof Map ? (Map<String, Object>) usageObj : null;
            sendDone(emitter, usage);
            isCompleted.set(true);
            emitter.complete();
        }).exceptionally(ex -> {
            if (!isCompleted.get()) {
                log.error("Stream chat failed for tenant: {}", identity.getTenantId(), ex);
                String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                sendError(emitter, message != null ? message : "Internal server error", "INTERNAL_ERROR");
            }
            return null;
        });

        // 心跳：等待响应期间定期发送
        CompletableFuture.runAsync(() -> {
            try {
                while (!isCompleted.get() && !responseFuture.isDone()) {
                    sendHeartbeat(emitter);
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, sseExecutor);

        return emitter;
    }
    
    /**
     * 发送内容块
     */
    private void sendChunk(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event()
                .name("chunk")
                .data(Map.of(
                    "content", content,
                    "finished", false,
                    "timestamp", System.currentTimeMillis()
                )));
        } catch (IOException e) {
            log.warn("Failed to send chunk, connection may be closed");
            emitter.completeWithError(e);
        }
    }
    
    /**
     * 发送完成事件
     */
    private void sendDone(SseEmitter emitter, Map<String, Object> usage) {
        try {
            emitter.send(SseEmitter.event()
                .name("done")
                .data(Map.of(
                    "status", "completed",
                    "usage", usage != null ? usage : Map.of(
                        "prompt_tokens", 0,
                        "completion_tokens", 0,
                        "total_tokens", 0
                    ),
                    "timestamp", System.currentTimeMillis()
                )));
        } catch (IOException e) {
            log.warn("Failed to send done event", e);
        }
    }
    
    /**
     * 发送错误事件
     */
    private void sendError(SseEmitter emitter, String message, String code) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data(Map.of(
                    "error", message,
                    "code", code,
                    "timestamp", System.currentTimeMillis()
                )));
            emitter.complete();
        } catch (IOException e) {
            log.error("Failed to send error event", e);
            emitter.completeWithError(e);
        }
    }
    
    /**
     * 创建错误发射器
     */
    private SseEmitter createErrorEmitter(String message, String code) {
        SseEmitter emitter = new SseEmitter();
        emitter.completeWithError(new RuntimeException(message + " (code: " + code + ")"));
        return emitter;
    }
    
    /**
     * 解析消息 JSON 字符串
     */
    private List<Map<String, Object>> parseMessages(String messagesJson) {
        try {
            return objectMapper.readValue(messagesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse messages JSON", e);
            throw new RuntimeException("Invalid messages format", e);
        }
    }

    private void sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                .name("heartbeat")
                .data(Map.of(
                    "status", "waiting",
                    "timestamp", System.currentTimeMillis()
                )));
        } catch (IOException e) {
            log.warn("Failed to send heartbeat", e);
        }
    }

    /**
     * Chat completion API
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestAttribute CallerIdentity identity,
            @RequestBody ChatRequest request) {
        
        if (identity == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Authentication required",
                "code", "AUTH_REQUIRED"
            ));
        }

        try {
            Map<String, Object> response = llmRouterService.chat(
                identity,
                request.getModel(),
                request.getMessages(),
                request.getTemperature(),
                request.getMaxTokens()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("LLM chat failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Chat completion failed",
                "message", e.getMessage(),
                "code", "CHAT_FAILED"
            ));
        }
    }

    /**
     * Embedding API
     */
    @PostMapping("/embed")
    public ResponseEntity<EmbeddingResponse> embed(
            @RequestAttribute CallerIdentity identity,
            @RequestBody EmbeddingRequest request) {
        
        if (identity == null) {
            return ResponseEntity.badRequest().body(EmbeddingResponse.error(
                "Authentication required", "AUTH_REQUIRED"
            ));
        }

        try {
            List<float[]> embeddings = llmRouterService.embed(
                identity,
                request.getModel(),
                request.getTexts()
            );
            
            return ResponseEntity.ok(EmbeddingResponse.success(embeddings));
        } catch (Exception e) {
            log.error("LLM embedding failed", e);
            return ResponseEntity.internalServerError().body(EmbeddingResponse.error(
                "Embedding failed: " + e.getMessage(), "EMBED_FAILED"
            ));
        }
    }

    /**
     * Get usage statistics
     */
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(
            @RequestAttribute CallerIdentity identity,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        
        if (identity == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Authentication required",
                "code", "AUTH_REQUIRED"
            ));
        }

        try {
            // 这里可以添加使用量统计逻辑
            // 暂时返回基本信息
            return ResponseEntity.ok(Map.of(
                "tenant_id", identity.getTenantId(),
                "message", "Usage statistics not implemented yet",
                "code", "NOT_IMPLEMENTED"
            ));
        } catch (Exception e) {
            log.error("Failed to get usage statistics", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to get usage statistics",
                "message", e.getMessage(),
                "code", "USAGE_FAILED"
            ));
        }
    }

    // Request DTOs
    public static class ChatRequest {
        private String model = "default";
        private List<Map<String, Object>> messages;
        private Double temperature;
        private Integer maxTokens;

        // Getters and Setters
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<Map<String, Object>> getMessages() { return messages; }
        public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class EmbeddingRequest {
        private String model = "default";
        private List<String> texts;

        // Getters and Setters
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<String> getTexts() { return texts; }
        public void setTexts(List<String> texts) { this.texts = texts; }
    }

    public static class EmbeddingResponse {
        private boolean success;
        private List<float[]> embeddings;
        private String error;
        private String code;

        public static EmbeddingResponse success(List<float[]> embeddings) {
            EmbeddingResponse response = new EmbeddingResponse();
            response.success = true;
            response.embeddings = embeddings;
            return response;
        }

        public static EmbeddingResponse error(String error, String code) {
            EmbeddingResponse response = new EmbeddingResponse();
            response.success = false;
            response.error = error;
            response.code = code;
            return response;
        }

        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public List<float[]> getEmbeddings() { return embeddings; }
        public void setEmbeddings(List<float[]> embeddings) { this.embeddings = embeddings; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }
}

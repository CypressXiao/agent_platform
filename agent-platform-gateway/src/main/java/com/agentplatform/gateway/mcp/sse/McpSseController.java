package com.agentplatform.gateway.mcp.sse;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * MCP SSE Transport 端点。
 * 客户端通过 SSE 长连接接收服务端推送的通知（如 tools/list_changed）。
 * 同时支持通过 SSE 发送 JSON-RPC 请求。
 */
@RestController
@RequestMapping("/mcp/v1")
@RequiredArgsConstructor
@Slf4j
public class McpSseController {

    private final ToolAggregator toolAggregator;
    private final ToolDispatcher toolDispatcher;
    private final Supplier<CallerIdentity> callerIdentityExtractor;
    private final McpSseSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    /**
     * SSE 连接端点。客户端建立长连接后，服务端可主动推送通知。
     * 返回的第一个事件是 endpoint 事件，告知客户端 JSON-RPC 请求应发送到哪里。
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sseConnect() {
        CallerIdentity identity = callerIdentityExtractor.get();
        String sessionId = UUID.randomUUID().toString();

        log.info("SSE connection established: sessionId={}, tenant={}", sessionId, identity.getTenantId());

        // 创建会话
        Sinks.Many<ServerSentEvent<String>> sink = sessionManager.createSession(sessionId, identity);

        // 发送 endpoint 事件（MCP 规范要求）
        String endpointEvent = buildEndpointEvent(sessionId);
        sink.tryEmitNext(ServerSentEvent.<String>builder()
            .event("endpoint")
            .data(endpointEvent)
            .build());

        // 发送初始化完成通知
        sink.tryEmitNext(ServerSentEvent.<String>builder()
            .event("message")
            .data(buildNotification("notifications/initialized", Map.of()))
            .build());

        // 返回 SSE 流，包含心跳
        return sink.asFlux()
            .mergeWith(heartbeat())
            .doOnCancel(() -> {
                log.info("SSE connection closed: sessionId={}", sessionId);
                sessionManager.removeSession(sessionId);
            })
            .doOnError(e -> {
                log.warn("SSE connection error: sessionId={}, error={}", sessionId, e.getMessage());
                sessionManager.removeSession(sessionId);
            });
    }

    /**
     * SSE 消息接收端点。客户端通过 POST 发送 JSON-RPC 请求，响应通过 SSE 推送。
     */
    @PostMapping("/sse/message")
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleSseMessage(
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {

        Sinks.Many<ServerSentEvent<String>> sink = sessionManager.getSink(sessionId);
        if (sink == null) {
            return errorResponse(request.get("id"), -32000, "Session not found: " + sessionId);
        }

        String method = (String) request.get("method");
        Object id = request.get("id");
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> handleToolsList(params);
                case "tools/call" -> handleToolsCall(params);
                default -> throw new IllegalArgumentException("Method not found: " + method);
            };

            Map<String, Object> response = successResponse(id, result);

            // 通过 SSE 推送响应
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                .event("message")
                .data(toJson(response))
                .build());

            return response;

        } catch (Exception e) {
            log.warn("SSE message handling error: method={}, error={}", method, e.getMessage());
            return errorResponse(id, -32603, e.getMessage());
        }
    }

    private String buildEndpointEvent(String sessionId) {
        return "/mcp/v1/sse/message?sessionId=" + sessionId;
    }

    private String buildNotification(String method, Map<String, Object> params) {
        return toJson(Map.of(
            "jsonrpc", "2.0",
            "method", method,
            "params", params
        ));
    }

    private Flux<ServerSentEvent<String>> heartbeat() {
        return Flux.interval(Duration.ofSeconds(30))
            .map(i -> ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .build());
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        return Map.of(
            "protocolVersion", "2024-11-05",
            "capabilities", Map.of(
                "tools", Map.of("listChanged", true)
            ),
            "serverInfo", Map.of(
                "name", "agent-platform-gateway",
                "version", "1.0.0"
            )
        );
    }

    private Map<String, Object> handleToolsList(Map<String, Object> params) {
        CallerIdentity identity = callerIdentityExtractor.get();
        List<ToolAggregator.ToolView> tools = toolAggregator.listTools(identity);

        List<Map<String, Object>> toolDefs = tools.stream()
            .map(view -> {
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("name", view.name());
                def.put("description", view.description());
                if (view.inputSchema() != null) {
                    def.put("inputSchema", view.inputSchema());
                }
                return def;
            })
            .toList();

        return Map.of("tools", toolDefs);
    }

    @SuppressWarnings("unchecked")
    private Object handleToolsCall(Map<String, Object> params) {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        CallerIdentity identity = callerIdentityExtractor.get();

        Object result = toolDispatcher.dispatch(identity, toolName, arguments);

        List<Map<String, Object>> content = new ArrayList<>();
        if (result instanceof String text) {
            content.add(Map.of("type", "text", "text", text));
        } else if (result != null) {
            content.add(Map.of("type", "text", "text", toJson(result)));
        }

        return Map.of("content", content, "isError", false);
    }

    private Map<String, Object> successResponse(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        return Map.of(
            "jsonrpc", "2.0",
            "id", id != null ? id : "null",
            "error", Map.of("code", code, "message", message)
        );
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}

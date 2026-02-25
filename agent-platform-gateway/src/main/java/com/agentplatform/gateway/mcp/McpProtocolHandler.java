package com.agentplatform.gateway.mcp;

import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * MCP Protocol Handler — JSON-RPC 2.0 endpoint.
 * Handles tools/list, tools/call, ping, and initialize methods.
 */
@RestController
@RequestMapping("/mcp/v1")
@RequiredArgsConstructor
@Slf4j
public class McpProtocolHandler {

    private final ToolAggregator toolAggregator;
    private final ToolDispatcher toolDispatcher;
    private final Supplier<CallerIdentity> callerIdentityExtractor;
    private final ObjectMapper objectMapper;

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        String jsonrpc = (String) request.getOrDefault("jsonrpc", "2.0");
        Object id = request.get("id");
        String method = (String) request.get("method");
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        if (method == null) {
            return ResponseEntity.badRequest().body(errorResponse(id, -32600, "Invalid Request: method is required"));
        }

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> handlePing();
                case "tools/list" -> handleToolsList(params);
                case "tools/call" -> handleToolsCall(params);
                case "notifications/initialized" -> {
                    yield null; // Notification, no response needed
                }
                default -> throw new IllegalArgumentException("Method not found: " + method);
            };

            if (result == null && method.startsWith("notifications/")) {
                return ResponseEntity.ok(Map.of());
            }

            return ResponseEntity.ok(successResponse(id, result));

        } catch (McpException e) {
            log.warn("MCP error: method={}, code={}", method, e.getErrorCode().getCode());
            return ResponseEntity.status(e.getHttpStatus())
                .body(errorResponse(id, mapErrorCode(e), e.getDetail()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(errorResponse(id, -32601, e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error handling MCP request: method={}", method, e);
            return ResponseEntity.internalServerError()
                .body(errorResponse(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        return Map.of(
            "protocolVersion", "2025-11-25",
            "capabilities", Map.of(
                "tools", Map.of("listChanged", true)
            ),
            "serverInfo", Map.of(
                "name", "agent-platform-gateway",
                "version", "1.0.0"
            )
        );
    }

    private Map<String, Object> handlePing() {
        return Map.of();
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
                if (view.shared()) {
                    Map<String, Object> annotations = new HashMap<>();
                    annotations.put("shared", true);
                    annotations.put("sharedFrom", view.sharedFrom());
                    def.put("annotations", annotations);
                }
                return def;
            })
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolDefs);

        // Pagination cursor (optional)
        String cursor = params != null ? (String) params.get("cursor") : null;
        if (cursor != null) {
            result.put("nextCursor", null);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Object handleToolsCall(Map<String, Object> params) {
        String toolName = (String) params.get("name");
        if (toolName == null) {
            throw new IllegalArgumentException("Tool name is required in tools/call params");
        }

        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        CallerIdentity identity = callerIdentityExtractor.get();

        Object result = toolDispatcher.dispatch(identity, toolName, arguments);

        // Wrap result in MCP content format
        List<Map<String, Object>> content = new ArrayList<>();
        if (result instanceof String text) {
            content.add(Map.of("type", "text", "text", text));
        } else if (result instanceof Map) {
            try {
                String json = objectMapper.writeValueAsString(result);
                content.add(Map.of("type", "text", "text", json));
            } catch (Exception e) {
                content.add(Map.of("type", "text", "text", result.toString()));
            }
        } else if (result != null) {
            try {
                String json = objectMapper.writeValueAsString(result);
                content.add(Map.of("type", "text", "text", json));
            } catch (Exception e) {
                content.add(Map.of("type", "text", "text", result.toString()));
            }
        }

        return Map.of("content", content, "isError", false);
    }

    private Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        return Map.of(
            "jsonrpc", "2.0",
            "id", id != null ? id : "null",
            "error", Map.of(
                "code", code,
                "message", message
            )
        );
    }

    private int mapErrorCode(McpException e) {
        return switch (e.getErrorCode().getHttpStatus()) {
            case 401 -> -32001;
            case 403 -> -32002;
            case 404 -> -32003;
            case 429 -> -32004;
            case 502, 503, 504 -> -32005;
            default -> -32603;
        };
    }
}

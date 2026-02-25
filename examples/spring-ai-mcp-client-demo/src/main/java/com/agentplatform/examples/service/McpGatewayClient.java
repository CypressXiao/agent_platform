package com.agentplatform.examples.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * MCP Gateway 客户端服务。
 * 
 * 演示如何调用 MCP Gateway 的各种端点。
 * WebClient 已配置 OAuth2，会自动处理 Token 获取和刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpGatewayClient {

    private final WebClient mcpWebClient;

    @Value("${mcp.gateway.url}")
    private String gatewayUrl;

    private int requestId = 0;

    /**
     * 调用 MCP initialize 方法。
     * 这是 MCP 协议的握手步骤。
     */
    public Mono<Map<String, Object>> initialize() {
        return callMcp("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "spring-ai-mcp-demo",
                        "version", "1.0.0"
                )
        ));
    }

    /**
     * 获取工具列表。
     * 
     * 调用 MCP 的 tools/list 方法，返回 Gateway 上所有可用的工具。
     */
    public Mono<Map<String, Object>> listTools() {
        return callMcp("tools/list", Map.of());
    }

    /**
     * 调用指定工具。
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     */
    public Mono<Map<String, Object>> callTool(String toolName, Map<String, Object> arguments) {
        return callMcp("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments
        ));
    }

    /**
     * 发送 MCP JSON-RPC 请求。
     * 
     * 注意：这里不需要手动处理 Token！
     * WebClient 的 OAuth2 过滤器会自动：
     * 1. 检查是否有有效 Token
     * 2. 如果没有或已过期，调用 /oauth2/token 获取
     * 3. 将 Token 添加到 Authorization 头
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> callMcp(String method, Map<String, Object> params) {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", method,
                "params", params
        );

        log.info("Calling MCP method: {}", method);

        return mcpWebClient.post()
                .uri(gatewayUrl + "/mcp/v1")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> (Map<String, Object>) response)
                .doOnNext(response -> log.info("MCP response: {}", response))
                .doOnError(error -> log.error("MCP call failed: {}", error.getMessage()));
    }
}

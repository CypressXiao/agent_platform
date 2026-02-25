package com.agentplatform.gateway.mcp.upstream;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Proxy service for forwarding tools/call to upstream MCP servers.
 * Handles token exchange and JSON-RPC protocol.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpProxyService {

    private final WebClient.Builder webClientBuilder;
    private final TokenExchangeService tokenExchange;

    @SuppressWarnings("unchecked")
    public Object forward(CallerIdentity identity, UpstreamServer server,
                          Tool tool, Map<String, Object> arguments) {
        // Build JSON-RPC request
        Map<String, Object> jsonRpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "tools/call",
            "params", Map.of(
                "name", tool.getToolName(),
                "arguments", arguments
            )
        );

        // Get upstream auth token (MUST NOT pass through caller's token)
        String authHeader = tokenExchange.getUpstreamAuth(identity, server);

        String mcpEndpoint = resolveMcpEndpoint(server);
        int timeoutMs = tool.getTimeoutMs() != null ? tool.getTimeoutMs() : 30000;

        try {
            WebClient client = webClientBuilder.build();
            WebClient.RequestBodySpec spec = client.post()
                .uri(mcpEndpoint);

            if (authHeader != null) {
                spec = (WebClient.RequestBodySpec) spec.header("Authorization", authHeader);
            }

            Map<String, Object> response = spec
                .bodyValue(jsonRpcRequest)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMillis(timeoutMs));

            if (response == null) {
                throw new McpException(McpErrorCode.UPSTREAM_TIMEOUT,
                    "No response from upstream MCP server: " + server.getServerId());
            }

            // Check for JSON-RPC error
            if (response.containsKey("error")) {
                Map<String, Object> error = (Map<String, Object>) response.get("error");
                String message = (String) error.getOrDefault("message", "Unknown upstream error");
                throw new McpException(McpErrorCode.INTERNAL_ERROR,
                    "Upstream MCP error: " + message);
            }

            return response.get("result");

        } catch (McpException e) {
            throw e;
        } catch (WebClientResponseException.GatewayTimeout e) {
            throw new McpException(McpErrorCode.UPSTREAM_TIMEOUT,
                "Upstream MCP server timed out: " + server.getServerId());
        } catch (Exception e) {
            log.error("MCP proxy error for server {}: {}", server.getServerId(), e.getMessage(), e);
            throw new McpException(McpErrorCode.UPSTREAM_UNHEALTHY,
                "Failed to call upstream MCP server: " + e.getMessage(), e);
        }
    }

    private String resolveMcpEndpoint(UpstreamServer server) {
        String baseUrl = server.getBaseUrl();
        if (baseUrl.endsWith("/mcp")) {
            return baseUrl;
        }
        return baseUrl + "/mcp";
    }
}

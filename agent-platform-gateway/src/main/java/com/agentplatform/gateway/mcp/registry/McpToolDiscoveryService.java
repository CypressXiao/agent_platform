package com.agentplatform.gateway.mcp.registry;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.gateway.mcp.upstream.TokenExchangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Discovers tools from an upstream MCP server by calling its tools/list endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolDiscoveryService {

    private final WebClient.Builder webClientBuilder;
    private final TokenExchangeService tokenExchange;

    @SuppressWarnings("unchecked")
    public List<Tool> discoverTools(UpstreamServer server) {
        WebClient client = webClientBuilder.build();

        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "tools/list",
            "params", Map.of()
        );

        // Get upstream auth if configured (supports both authenticated and unauthenticated MCP servers)
        TokenExchangeService.AuthHeader authHeader = tokenExchange.getUpstreamAuth(null, server);

        Map<String, Object> response;
        try {
            WebClient.RequestBodySpec spec = client.post()
                .uri(server.getBaseUrl());
            
            if (authHeader != null) {
                spec = (WebClient.RequestBodySpec) spec.header(authHeader.headerName(), authHeader.headerValue());
            }
            
            response = spec
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("Failed to discover tools from {}: {}", server.getServerId(), e.getMessage());
            return List.of();
        }

        if (response == null || !response.containsKey("result")) {
            return List.of();
        }

        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<Map<String, Object>> toolDefs = (List<Map<String, Object>>) result.get("tools");
        if (toolDefs == null) {
            return List.of();
        }

        List<Tool> tools = new ArrayList<>();
        for (Map<String, Object> def : toolDefs) {
            String name = (String) def.get("name");
            String description = (String) def.get("description");
            Map<String, Object> inputSchema = (Map<String, Object>) def.get("inputSchema");

            Tool tool = Tool.builder()
                .toolId(server.getServerId() + ":" + name)
                .toolName(name)
                .description(description)
                .sourceType("upstream_mcp")
                .sourceId(server.getServerId())
                .ownerTid(server.getOwnerTid())
                .inputSchema(inputSchema)
                .status("active")
                .build();
            tools.add(tool);
        }

        log.info("Discovered {} tools from MCP server {}", tools.size(), server.getServerId());
        return tools;
    }
}

package com.agentplatform.gateway.mcp;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * Configuration for the MCP Server layer.
 * Bridges Spring AI MCP with the gateway's dynamic tool routing.
 */
@Configuration
@RequiredArgsConstructor
public class McpServerConfig {

    private final ToolAggregator toolAggregator;
    private final ToolDispatcher toolDispatcher;
    private final Supplier<CallerIdentity> callerIdentityExtractor;

    @Bean
    public DynamicToolCallbackProvider dynamicToolCallbackProvider() {
        return new DynamicToolCallbackProvider(toolAggregator, toolDispatcher, callerIdentityExtractor);
    }
}

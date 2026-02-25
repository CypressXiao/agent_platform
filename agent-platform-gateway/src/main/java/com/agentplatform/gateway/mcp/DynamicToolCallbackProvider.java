package com.agentplatform.gateway.mcp;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

/**
 * Dynamically provides tool callbacks based on the caller's identity.
 * Each call to getToolCallbacks() returns the tools visible to the current caller.
 */
@RequiredArgsConstructor
@Slf4j
public class DynamicToolCallbackProvider {

    private final ToolAggregator toolAggregator;
    private final ToolDispatcher toolDispatcher;
    private final Supplier<CallerIdentity> callerIdentityExtractor;

    /**
     * Returns all tool callbacks visible to the current caller.
     */
    public List<GatewayToolCallback> getToolCallbacks() {
        CallerIdentity identity = callerIdentityExtractor.get();
        List<ToolAggregator.ToolView> tools = toolAggregator.listTools(identity);

        return tools.stream()
            .map(view -> new GatewayToolCallback(
                view.name(),
                view.description(),
                view.inputSchema(),
                toolDispatcher,
                callerIdentityExtractor
            ))
            .toList();
    }
}

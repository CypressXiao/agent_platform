package com.agentplatform.gateway.mcp.builtin;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Built-in echo tool for testing and diagnostics.
 */
@Component
public class EchoToolHandler implements BuiltinToolHandler {

    @Override
    public String toolName() {
        return "echo";
    }

    @Override
    public String description() {
        return "Echo back the input with caller metadata. Useful for testing connectivity and authentication.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "message", Map.of("type", "string", "description", "Message to echo back")
            ),
            "required", java.util.List.of("message")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        return Map.of(
            "echo", arguments.getOrDefault("message", ""),
            "caller", Map.of(
                "tenant_id", identity.getTenantId(),
                "client_id", identity.getClientId()
            ),
            "timestamp", Instant.now().toString()
        );
    }
}

package com.agentplatform.gateway.mcp;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Individual tool callback that bridges MCP protocol calls to the ToolDispatcher.
 * Each instance represents one tool visible to the caller.
 */
@Slf4j
@Getter
public class GatewayToolCallback {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final ToolDispatcher toolDispatcher;
    private final Supplier<CallerIdentity> callerIdentityExtractor;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public GatewayToolCallback(String name, String description,
                                Map<String, Object> inputSchema,
                                ToolDispatcher toolDispatcher,
                                Supplier<CallerIdentity> callerIdentityExtractor) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.toolDispatcher = toolDispatcher;
        this.callerIdentityExtractor = callerIdentityExtractor;
    }

    /**
     * Execute the tool call. Called by the MCP protocol handler.
     */
    public Object call(String toolInput) {
        CallerIdentity identity = callerIdentityExtractor.get();
        Map<String, Object> arguments;
        try {
            arguments = OBJECT_MAPPER.readValue(toolInput, new TypeReference<>() {});
        } catch (Exception e) {
            arguments = Map.of("raw_input", toolInput);
        }
        return toolDispatcher.dispatch(identity, name, arguments);
    }

    /**
     * Execute the tool call with pre-parsed arguments.
     */
    public Object call(Map<String, Object> arguments) {
        CallerIdentity identity = callerIdentityExtractor.get();
        return toolDispatcher.dispatch(identity, name, arguments);
    }
}

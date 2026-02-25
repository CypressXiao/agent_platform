package com.agentplatform.gateway.mcp.registry;

import com.agentplatform.common.model.CallerIdentity;

import java.util.Map;

/**
 * Interface for built-in tool implementations.
 * Each built-in tool must implement this interface and register itself.
 */
public interface BuiltinToolHandler {

    /**
     * Unique tool name (e.g., "rag_search", "db_query").
     */
    String toolName();

    /**
     * Tool description for tools/list.
     */
    String description();

    /**
     * JSON Schema for the tool's input parameters.
     */
    Map<String, Object> inputSchema();

    /**
     * Execute the tool with the given arguments.
     */
    Object execute(CallerIdentity identity, Map<String, Object> arguments);
}

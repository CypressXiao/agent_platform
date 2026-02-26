package com.agentplatform.gateway.vector;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量删除工具
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorDeleteTool implements BuiltinToolHandler {

    private final VectorStoreService vectorStoreService;

    @Override
    public String toolName() {
        return "vector_delete";
    }

    @Override
    public String description() {
        return "Delete documents from vector database by IDs.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "collection", Map.of(
                    "type", "string",
                    "description", "Collection name"
                ),
                "ids", Map.of(
                    "type", "array",
                    "description", "List of document IDs to delete",
                    "items", Map.of("type", "string")
                )
            ),
            "required", List.of("collection", "ids")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String collection = (String) arguments.get("collection");
        List<String> ids = (List<String>) arguments.get("ids");

        vectorStoreService.delete(identity, collection, ids);

        return Map.of(
            "success", true,
            "collection", collection,
            "deleted_count", ids.size()
        );
    }
}

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
 * 向量检索工具
 * 基于相似度搜索向量库
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorSearchTool implements BuiltinToolHandler {

    private final VectorStoreService vectorStoreService;

    @Override
    public String toolName() {
        return "vector_search";
    }

    @Override
    public String description() {
        return "Search similar documents from vector database using semantic similarity. " +
               "Returns top-k most relevant documents.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "collection", Map.of(
                    "type", "string",
                    "description", "Collection name to search in"
                ),
                "query", Map.of(
                    "type", "string",
                    "description", "Query text for similarity search"
                ),
                "top_k", Map.of(
                    "type", "integer",
                    "description", "Number of results to return (default: 5)"
                ),
                "similarity_threshold", Map.of(
                    "type", "number",
                    "description", "Minimum similarity score (0-1, default: 0.7)"
                )
            ),
            "required", List.of("collection", "query")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String collection = (String) arguments.get("collection");
        String query = (String) arguments.get("query");
        int topK = arguments.containsKey("top_k") ? ((Number) arguments.get("top_k")).intValue() : 5;
        Double threshold = arguments.containsKey("similarity_threshold") 
            ? ((Number) arguments.get("similarity_threshold")).doubleValue() 
            : null;

        List<VectorStoreService.SearchResult> results = vectorStoreService.search(
            identity, collection, query, topK, threshold);

        return Map.of(
            "success", true,
            "collection", collection,
            "query", query,
            "result_count", results.size(),
            "results", results
        );
    }
}

package com.agentplatform.gateway.vector;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向量存储工具
 * 将文本自动 Embedding 并存储到向量库
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorStoreTool implements BuiltinToolHandler {

    private final VectorStoreService vectorStoreService;

    @Override
    public String toolName() {
        return "vector_store";
    }

    @Override
    public String description() {
        return "Store documents to vector database with automatic embedding. " +
               "Supports batch storage with metadata.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "collection", Map.of(
                    "type", "string",
                    "description", "Collection name for organizing documents"
                ),
                "documents", Map.of(
                    "type", "array",
                    "description", "List of documents to store",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Document ID (auto-generated if not provided)"),
                            "content", Map.of("type", "string", "description", "Document content to embed and store"),
                            "metadata", Map.of("type", "object", "description", "Optional metadata")
                        ),
                        "required", List.of("content")
                    )
                )
            ),
            "required", List.of("collection", "documents")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String collection = (String) arguments.get("collection");
        List<Map<String, Object>> documentsRaw = (List<Map<String, Object>>) arguments.get("documents");

        List<VectorStoreService.DocumentInput> documents = documentsRaw.stream()
            .map(doc -> VectorStoreService.DocumentInput.builder()
                .id(doc.containsKey("id") ? (String) doc.get("id") : UUID.randomUUID().toString())
                .content((String) doc.get("content"))
                .metadata(doc.containsKey("metadata") ? (Map<String, Object>) doc.get("metadata") : null)
                .build())
            .collect(Collectors.toList());

        List<String> storedIds = vectorStoreService.store(identity, collection, documents);

        return Map.of(
            "success", true,
            "collection", collection,
            "stored_count", storedIds.size(),
            "document_ids", storedIds
        );
    }
}

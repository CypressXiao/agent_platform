package com.agentplatform.gateway.memory;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.memory.model.EntityMemory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Unified memory query tool supporting four modes:
 * - recent:   Redis short-term memory (most recent entries)
 * - semantic: Mem0 semantic search (if enabled) or PG fallback
 * - exact:    PostgreSQL exact query (always available)
 * - entity:   PostgreSQL structured entity query
 */
@Component
@ConditionalOnProperty(name = "agent-platform.memory.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MemoryQueryTool implements BuiltinToolHandler {

    private final MemoryService memoryService;

    @Override
    public String toolName() {
        return "memory_query";
    }

    @Override
    public String description() {
        return "Query memories for a given agent. Supports four modes: " +
               "'recent' (short-term from Redis), 'semantic' (Mem0 vector search or PG fallback), " +
               "'exact' (PG exact match), 'entity' (structured key-value lookup).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "agent_id", Map.of("type", "string", "description", "Agent identifier"),
                "query", Map.of("type", "string", "description", "Search query (natural language for semantic, ignored for recent/entity)"),
                "mode", Map.of("type", "string", "description", "Query mode: recent | semantic | exact | entity", "default", "semantic",
                    "enum", List.of("recent", "semantic", "exact", "entity")),
                "top_k", Map.of("type", "integer", "description", "Number of results", "default", 5),
                "namespace", Map.of("type", "string", "description", "Namespace filter (for recent/semantic/exact)"),
                "entity_type", Map.of("type", "string", "description", "Entity type filter (for mode=entity)")
            ),
            "required", List.of("agent_id")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String agentId = (String) arguments.get("agent_id");
        String mode = (String) arguments.getOrDefault("mode", "semantic");
        String query = (String) arguments.getOrDefault("query", "");
        String namespace = (String) arguments.getOrDefault("namespace", "default");
        int topK = arguments.containsKey("top_k") ? ((Number) arguments.get("top_k")).intValue() : 5;

        return switch (mode) {
            case "recent" -> {
                List<Map<String, Object>> results = memoryService.queryRecent(identity, agentId, namespace, topK);
                yield Map.of("mode", "recent", "results", results, "total", results.size());
            }
            case "entity" -> {
                String entityType = (String) arguments.get("entity_type");
                List<EntityMemory> entities = memoryService.queryEntities(identity, agentId, entityType);
                List<Map<String, Object>> results = entities.stream()
                    .map(e -> Map.<String, Object>of(
                        "entity_id", e.getEntityId(),
                        "entity_type", e.getEntityType(),
                        "entity_key", e.getEntityKey(),
                        "value", e.getEntityValue(),
                        "metadata", e.getMetadata() != null ? e.getMetadata() : Map.of(),
                        "updated_at", e.getUpdatedAt().toString()
                    ))
                    .toList();
                yield Map.of("mode", "entity", "results", results, "total", results.size());
            }
            case "exact" -> {
                var entries = memoryService.list(identity, agentId, namespace, 0, topK);
                List<Map<String, Object>> results = entries.getContent().stream()
                    .map(e -> Map.<String, Object>of(
                        "memory_id", e.getMemoryId(),
                        "content", e.getContent(),
                        "namespace", e.getNamespace(),
                        "metadata", e.getMetadata() != null ? e.getMetadata() : Map.of(),
                        "created_at", e.getCreatedAt().toString()
                    ))
                    .toList();
                yield Map.of("mode", "exact", "results", results, "total", entries.getTotalElements());
            }
            default -> { // "semantic"
                Object result = memoryService.queryLongTerm(identity, agentId, query, namespace, topK);
                yield Map.of("mode", memoryService.isMem0Enabled() ? "semantic" : "exact_fallback", "result", result);
            }
        };
    }
}

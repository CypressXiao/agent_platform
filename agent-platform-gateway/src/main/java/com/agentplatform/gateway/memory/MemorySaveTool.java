package com.agentplatform.gateway.memory;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Unified memory save tool supporting three memory types:
 * - short: Redis short-term memory (conversation context, auto-expire)
 * - long:  Mem0 (if enabled) or PostgreSQL fallback (persistent)
 * - entity: PostgreSQL structured key-value memory
 */
@Component
@ConditionalOnProperty(name = "agent-platform.memory.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MemorySaveTool implements BuiltinToolHandler {

    private final MemoryService memoryService;

    @Override
    public String toolName() {
        return "memory_save";
    }

    @Override
    public String description() {
        return "Save a memory entry. Supports three types: 'short' (conversation context, auto-expire), " +
               "'long' (persistent, semantic-searchable when Mem0 is enabled), " +
               "'entity' (structured key-value facts/preferences).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "agent_id", Map.of("type", "string", "description", "Agent identifier"),
                "content", Map.of("type", "string", "description", "Memory content"),
                "type", Map.of("type", "string", "description", "Memory type: short | long | entity", "default", "long",
                    "enum", List.of("short", "long", "entity")),
                "namespace", Map.of("type", "string", "description", "Namespace (for short/long)", "default", "default"),
                "metadata", Map.of("type", "object", "description", "Custom metadata"),
                "ttl_seconds", Map.of("type", "integer", "description", "TTL in seconds (for short/long), 0 = never expire"),
                "entity_type", Map.of("type", "string", "description", "Entity type (for type=entity), e.g. user_preference, fact, profile"),
                "entity_key", Map.of("type", "string", "description", "Entity key (for type=entity), e.g. favorite_color, name")
            ),
            "required", List.of("agent_id", "content")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String agentId = (String) arguments.get("agent_id");
        String content = (String) arguments.get("content");
        String type = (String) arguments.getOrDefault("type", "long");
        String namespace = (String) arguments.getOrDefault("namespace", "default");
        Map<String, Object> metadata = (Map<String, Object>) arguments.get("metadata");
        int ttl = arguments.containsKey("ttl_seconds") ? ((Number) arguments.get("ttl_seconds")).intValue() : 0;

        return switch (type) {
            case "short" -> {
                long ttlSec = ttl > 0 ? ttl : 3600; // default 1 hour for short-term
                Map<String, Object> result = memoryService.saveShortTerm(identity, agentId, content, namespace, metadata, ttlSec);
                yield Map.of("type", "short", "result", result);
            }
            case "entity" -> {
                String entityType = (String) arguments.getOrDefault("entity_type", "fact");
                String entityKey = (String) arguments.getOrDefault("entity_key", "default");
                var entity = memoryService.saveEntity(identity, agentId, entityType, entityKey, content, metadata);
                yield Map.of(
                    "type", "entity",
                    "entity_id", entity.getEntityId(),
                    "entity_type", entity.getEntityType(),
                    "entity_key", entity.getEntityKey(),
                    "updated_at", entity.getUpdatedAt().toString()
                );
            }
            default -> { // "long"
                Map<String, Object> result = memoryService.saveLongTerm(identity, agentId, content, namespace, metadata, ttl);
                yield Map.of("type", "long", "result", result);
            }
        };
    }
}

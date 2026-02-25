package com.agentplatform.gateway.memory;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.memory.client.Mem0RestClient;
import com.agentplatform.gateway.memory.model.EntityMemory;
import com.agentplatform.gateway.memory.model.MemoryEntry;
import com.agentplatform.gateway.memory.model.MemoryNamespace;
import com.agentplatform.gateway.memory.repository.EntityMemoryRepository;
import com.agentplatform.gateway.memory.repository.MemoryEntryRepository;
import com.agentplatform.gateway.memory.repository.MemoryNamespaceRepository;
import com.agentplatform.gateway.memory.store.ShortTermMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Layered memory service with three tiers:
 * - Short-term: Redis (conversation context, TTL auto-expire)
 * - Long-term:  Mem0 (ON) → intelligent extraction + semantic search via Milvus
 *               MySQL (OFF) → fallback exact query
 * - Structured: MySQL entity_memory table (key-value facts, profiles)
 */
@Service
@ConditionalOnProperty(name = "agent-platform.memory.enabled", havingValue = "true")
@Slf4j
public class MemoryService {

    private final MemoryEntryRepository entryRepo;
    private final MemoryNamespaceRepository nsRepo;
    private final EntityMemoryRepository entityRepo;
    private final ShortTermMemoryStore shortTermStore;
    private final Mem0RestClient mem0Client; // nullable when mem0 disabled
    private final boolean mem0Enabled;

    public MemoryService(MemoryEntryRepository entryRepo,
                         MemoryNamespaceRepository nsRepo,
                         EntityMemoryRepository entityRepo,
                         ShortTermMemoryStore shortTermStore,
                         @Value("${agent-platform.memory.mem0.enabled:false}") boolean mem0Enabled,
                         Optional<Mem0RestClient> mem0Client) {
        this.entryRepo = entryRepo;
        this.nsRepo = nsRepo;
        this.entityRepo = entityRepo;
        this.shortTermStore = shortTermStore;
        this.mem0Enabled = mem0Enabled;
        this.mem0Client = mem0Client.orElse(null);
    }

    // ─── Short-term Memory (Redis) ───

    public Map<String, Object> saveShortTerm(CallerIdentity identity, String agentId,
                                              String content, String namespace,
                                              Map<String, Object> metadata, long ttlSeconds) {
        return shortTermStore.save(identity.getTenantId(), agentId, namespace, content, metadata, ttlSeconds);
    }

    public List<Map<String, Object>> queryRecent(CallerIdentity identity, String agentId,
                                                   String namespace, int limit) {
        return shortTermStore.queryRecent(identity.getTenantId(), agentId, namespace, limit);
    }

    public void clearShortTerm(CallerIdentity identity, String agentId, String namespace) {
        shortTermStore.clear(identity.getTenantId(), agentId, namespace);
    }

    // ─── Long-term Memory (Mem0 or PG fallback) ───

    @Transactional
    public Map<String, Object> saveLongTerm(CallerIdentity identity, String agentId, String content,
                                             String namespace, Map<String, Object> metadata, int ttlSeconds) {
        String ownerTid = identity.getTenantId();

        if (mem0Enabled && mem0Client != null) {
            // Delegate to Mem0: LLM extraction + vectorization + Milvus storage
            String userId = ownerTid + ":" + agentId;
            List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", content)
            );
            Map<String, Object> mem0Meta = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            mem0Meta.put("namespace", namespace);
            mem0Meta.put("source", "agent-platform");

            Map<String, Object> result = mem0Client.addMemory(messages, userId, agentId, mem0Meta);
            log.debug("Mem0 addMemory result: {}", result);
            return result;
        } else {
            // Fallback: save to PostgreSQL
            MemoryNamespace ns = nsRepo.findByOwnerTidAndName(ownerTid, namespace).orElse(null);
            if (ns != null) {
                long count = entryRepo.countByOwnerTidAndNamespace(ownerTid, namespace);
                if (count >= ns.getMaxEntries()) {
                    throw new McpException(McpErrorCode.MEMORY_QUOTA_EXCEEDED,
                        "Namespace '%s' has reached max entries: %d".formatted(namespace, ns.getMaxEntries()));
                }
            }

            Instant expiresAt = null;
            if (ttlSeconds > 0) {
                expiresAt = Instant.now().plusSeconds(ttlSeconds);
            } else if (ns != null && ns.getDefaultTtlSeconds() > 0) {
                expiresAt = Instant.now().plusSeconds(ns.getDefaultTtlSeconds());
            }

            MemoryEntry entry = MemoryEntry.builder()
                .memoryId(UUID.randomUUID().toString())
                .ownerTid(ownerTid)
                .agentId(agentId)
                .namespace(namespace)
                .content(content)
                .metadata(metadata)
                .expiresAt(expiresAt)
                .build();

            MemoryEntry saved = entryRepo.save(entry);
            return Map.of(
                "memory_id", saved.getMemoryId(),
                "namespace", saved.getNamespace(),
                "created_at", saved.getCreatedAt().toString()
            );
        }
    }

    public Object queryLongTerm(CallerIdentity identity, String agentId, String query,
                                String namespace, int limit) {
        String ownerTid = identity.getTenantId();

        if (mem0Enabled && mem0Client != null) {
            // Semantic search via Mem0 → Milvus
            String userId = ownerTid + ":" + agentId;
            return mem0Client.searchMemory(query, userId, agentId, limit);
        } else {
            // Fallback: exact query from PostgreSQL
            Page<MemoryEntry> entries = entryRepo.findByOwnerTidAndAgentIdAndNamespace(
                ownerTid, agentId, namespace,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")));

            List<Map<String, Object>> results = entries.getContent().stream()
                .map(e -> Map.<String, Object>of(
                    "memory_id", e.getMemoryId(),
                    "content", e.getContent(),
                    "namespace", e.getNamespace(),
                    "metadata", e.getMetadata() != null ? e.getMetadata() : Map.of(),
                    "created_at", e.getCreatedAt().toString()
                ))
                .toList();

            return Map.of("results", results, "total", entries.getTotalElements());
        }
    }

    @Transactional
    public void deleteLongTerm(CallerIdentity identity, String memoryId) {
        if (mem0Enabled && mem0Client != null) {
            mem0Client.deleteMemory(memoryId);
        } else {
            MemoryEntry entry = entryRepo.findById(memoryId)
                .orElseThrow(() -> new McpException(McpErrorCode.MEMORY_NAMESPACE_NOT_FOUND,
                    "Memory not found: " + memoryId));
            if (!identity.getTenantId().equals(entry.getOwnerTid())) {
                throw new McpException(McpErrorCode.FORBIDDEN_POLICY, "Not authorized to delete this memory");
            }
            entryRepo.deleteById(memoryId);
        }
    }

    // ─── Structured Entity Memory (PostgreSQL) ───

    @Transactional
    public EntityMemory saveEntity(CallerIdentity identity, String agentId,
                                    String entityType, String key, String value,
                                    Map<String, Object> metadata) {
        String ownerTid = identity.getTenantId();

        // Upsert: update if exists, create if not
        Optional<EntityMemory> existing = entityRepo
            .findByOwnerTidAndAgentIdAndEntityTypeAndEntityKey(ownerTid, agentId, entityType, key);

        if (existing.isPresent()) {
            EntityMemory entity = existing.get();
            entity.setEntityValue(value);
            entity.setMetadata(metadata);
            entity.setUpdatedAt(Instant.now());
            return entityRepo.save(entity);
        } else {
            EntityMemory entity = EntityMemory.builder()
                .entityId(UUID.randomUUID().toString())
                .ownerTid(ownerTid)
                .agentId(agentId)
                .entityType(entityType)
                .entityKey(key)
                .entityValue(value)
                .metadata(metadata)
                .build();
            return entityRepo.save(entity);
        }
    }

    public List<EntityMemory> queryEntities(CallerIdentity identity, String agentId, String entityType) {
        String ownerTid = identity.getTenantId();
        if (entityType != null && !entityType.isEmpty()) {
            return entityRepo.findByOwnerTidAndAgentIdAndEntityType(ownerTid, agentId, entityType);
        }
        return entityRepo.findByOwnerTidAndAgentId(ownerTid, agentId);
    }

    public Optional<EntityMemory> getEntity(CallerIdentity identity, String agentId,
                                             String entityType, String key) {
        return entityRepo.findByOwnerTidAndAgentIdAndEntityTypeAndEntityKey(
            identity.getTenantId(), agentId, entityType, key);
    }

    // ─── Legacy compatibility ───

    public Page<MemoryEntry> list(CallerIdentity identity, String agentId, String namespace, int page, int size) {
        return entryRepo.findByOwnerTidAndAgentIdAndNamespace(
            identity.getTenantId(), agentId, namespace,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional
    public void clearNamespace(CallerIdentity identity, String agentId, String namespace) {
        entryRepo.clearNamespace(identity.getTenantId(), agentId, namespace);
        if (mem0Enabled && mem0Client != null) {
            String userId = identity.getTenantId() + ":" + agentId;
            try {
                mem0Client.resetMemories(userId);
            } catch (Exception e) {
                log.warn("Failed to reset Mem0 memories for {}: {}", userId, e.getMessage());
            }
        }
    }

    // ─── Scheduled cleanup ───

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void cleanupExpired() {
        entryRepo.deleteExpired();
    }

    // ─── Status ───

    public boolean isMem0Enabled() {
        return mem0Enabled;
    }

    public boolean isMem0Healthy() {
        return mem0Enabled && mem0Client != null && mem0Client.isHealthy();
    }
}

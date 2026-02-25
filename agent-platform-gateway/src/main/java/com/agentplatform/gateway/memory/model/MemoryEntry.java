package com.agentplatform.gateway.memory.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "memory_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    @Id
    @Column(name = "memory_id")
    private String memoryId;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(nullable = false)
    @Builder.Default
    private String namespace = "default";

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> metadata;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

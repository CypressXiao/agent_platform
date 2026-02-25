package com.agentplatform.gateway.memory.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "memory_namespace")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryNamespace {

    @Id
    @Column(name = "namespace_id")
    private String namespaceId;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Column(nullable = false)
    private String name;

    @Column(name = "max_entries")
    @Builder.Default
    private Integer maxEntries = 10000;

    @Column(name = "max_size_bytes")
    @Builder.Default
    private Long maxSizeBytes = 104857600L;

    @Column(name = "default_ttl_seconds")
    @Builder.Default
    private Integer defaultTtlSeconds = 0;

    @Column(name = "embedding_model")
    @Builder.Default
    private String embeddingModel = "text-embedding-3-small";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

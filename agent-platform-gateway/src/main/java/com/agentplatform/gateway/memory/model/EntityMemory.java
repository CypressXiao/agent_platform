package com.agentplatform.gateway.memory.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

/**
 * Structured entity memory stored in PostgreSQL.
 * Stores key-value facts, user profiles, entity relationships, and preferences.
 */
@Entity
@Table(name = "entity_memory", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"owner_tid", "agent_id", "entity_type", "entity_key"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityMemory {

    @Id
    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "entity_type", nullable = false)
    private String entityType; // e.g. "user_preference", "fact", "profile", "relationship"

    @Column(name = "entity_key", nullable = false)
    private String entityKey; // e.g. "favorite_color", "name", "location"

    @Column(name = "entity_value", nullable = false, columnDefinition = "text")
    private String entityValue;

    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> metadata;

    @Column(name = "confidence")
    @Builder.Default
    private Double confidence = 1.0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

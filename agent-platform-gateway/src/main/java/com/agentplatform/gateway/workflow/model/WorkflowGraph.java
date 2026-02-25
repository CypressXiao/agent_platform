package com.agentplatform.gateway.workflow.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "workflow_graph")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowGraph {

    @Id
    @Column(name = "graph_id")
    private String graphId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Type(JsonType.class)
    @Column(name = "definition", columnDefinition = "json", nullable = false)
    private Map<String, Object> definition;

    @Column(nullable = false)
    @Builder.Default
    private String status = "draft";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

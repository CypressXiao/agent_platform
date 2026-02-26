package com.agentplatform.gateway.prompt.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;

/**
 * Prompt 模板实体
 */
@Entity
@Table(name = "prompt_template")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplate {

    @Id
    @Column(name = "template_id")
    private String templateId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "template", nullable = false, columnDefinition = "TEXT")
    private String template;

    @Type(JsonType.class)
    @Column(name = "variables", columnDefinition = "json")
    private List<String> variables;

    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    @Column(name = "status")
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

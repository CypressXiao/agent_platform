package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tenant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id
    @Column(name = "tid")
    private String tid;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Type(JsonType.class)
    @Column(name = "quota_config", columnDefinition = "json")
    private Map<String, Object> quotaConfig;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

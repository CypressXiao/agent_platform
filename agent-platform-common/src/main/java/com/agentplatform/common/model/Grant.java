package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "grant_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Grant {

    @Id
    @Column(name = "grant_id")
    private String grantId;

    @Column(name = "grantor_tid", nullable = false)
    private String grantorTid;

    @Column(name = "grantee_tid", nullable = false)
    private String granteeTid;

    @Type(JsonType.class)
    @Column(name = "tools", columnDefinition = "json")
    private List<String> tools;

    @Type(JsonType.class)
    @Column(name = "scopes", columnDefinition = "json")
    private List<String> scopes;

    @Type(JsonType.class)
    @Column(name = "constraints", columnDefinition = "json")
    private Map<String, Object> constraints;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

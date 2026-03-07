package com.agentplatform.gateway.feishu.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "feishu_space_registry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuSpaceRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_token", nullable = false, unique = true)
    private String spaceToken;

    @Column(name = "space_type", nullable = false)
    @Builder.Default
    private String spaceType = "WIKI";

    @Column
    private String name;

    @Column(name = "default_profile", nullable = false)
    @Builder.Default
    private String defaultProfile = "knowledge";

    @Column(nullable = false)
    private String tenant;

    @Column
    private String scene;

    @Column(name = "auto_discover", nullable = false)
    @Builder.Default
    private Boolean autoDiscover = true;

    @Column(name = "sync_enabled", nullable = false)
    @Builder.Default
    private Boolean syncEnabled = true;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> metadata;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public enum SpaceType {
        WIKI, FOLDER
    }

    public enum Status {
        ACTIVE, DISABLED, PERMISSION_DENIED
    }
}

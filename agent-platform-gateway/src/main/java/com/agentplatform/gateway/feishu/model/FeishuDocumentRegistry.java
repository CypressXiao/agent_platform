package com.agentplatform.gateway.feishu.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "feishu_document_registry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuDocumentRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_token", nullable = false, unique = true)
    private String docToken;

    @Column(name = "doc_type", nullable = false)
    @Builder.Default
    private String docType = "docx";

    @Column(name = "source_type", nullable = false)
    @Builder.Default
    private String sourceType = "SINGLE";

    @Column
    private String title;

    @Column
    private String url;

    @Column(nullable = false)
    @Builder.Default
    private String profile = "knowledge";

    @Column
    private String collection;

    @Column(nullable = false)
    private String tenant;

    @Column
    private String scene;

    @Column(name = "last_revision")
    private String lastRevision;

    @Column(name = "last_hash")
    private String lastHash;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "parent_token")
    private String parentToken;

    @Column(name = "space_id")
    private String spaceId;

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

    public enum Status {
        ACTIVE, DELETED, PERMISSION_DENIED, DISABLED
    }

    public enum SourceType {
        SINGLE, WIKI, FOLDER, WEBHOOK
    }

    public enum DocType {
        docx, sheet, wiki, folder
    }
}

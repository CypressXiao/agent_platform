package com.agentplatform.gateway.feishu.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "feishu_sync_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuSyncAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_token", nullable = false)
    private String docToken;

    @Column(name = "task_id")
    private String taskId;

    @Column(nullable = false)
    private String operation;

    @Column(name = "trigger_type")
    private String triggerType;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "old_revision")
    private String oldRevision;

    @Column(name = "new_revision")
    private String newRevision;

    @Column(name = "old_status")
    private String oldStatus;

    @Column(name = "new_status")
    private String newStatus;

    @Column(nullable = false)
    @Builder.Default
    private Boolean success = true;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column
    private String collection;

    @Column(name = "chunks_affected")
    private Integer chunksAffected;

    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public enum Operation {
        SYNC, DELETE, REGISTER, UNREGISTER, PERMISSION_CHANGE
    }
}

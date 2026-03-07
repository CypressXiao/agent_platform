package com.agentplatform.gateway.feishu.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "feishu_sync_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuSyncTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "doc_token", nullable = false)
    private String docToken;

    @Column
    private String revision;

    @Column(name = "trigger_type", nullable = false)
    @Builder.Default
    private String triggerType = "SCHEDULED";

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "chunks_created")
    private Integer chunksCreated;

    @Type(JsonType.class)
    @Column(columnDefinition = "json")
    private Map<String, Object> metadata;

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
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    public enum TriggerType {
        SCHEDULED, MANUAL, WEBHOOK, INIT
    }
}

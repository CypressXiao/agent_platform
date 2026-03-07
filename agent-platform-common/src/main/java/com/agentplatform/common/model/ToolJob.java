package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tool_job", indexes = {
    @Index(name = "idx_run_id", columnList = "run_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolJob {

    @Id
    @Column(name = "job_id")
    private String jobId;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "caller_tid", nullable = false)
    private String callerTid;

    @Type(JsonType.class)
    @Column(name = "arguments", columnDefinition = "json")
    private Map<String, Object> arguments;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PENDING"; // PENDING | RUNNING | SUCCEEDED | FAILED | TIMEOUT

    @Type(JsonType.class)
    @Column(name = "result", columnDefinition = "json")
    private Map<String, Object> result;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "estimated_wait_seconds")
    private Integer estimatedWaitSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "timeout_at")
    private Instant timeoutAt;
}

package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @Column(name = "log_id")
    private String logId;

    @Column(name = "timestamp", nullable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "caller_id")
    private String callerId;

    @Column(name = "actor_tid")
    private String actorTid;

    @Column(name = "owner_tid")
    private String ownerTid;

    @Column(name = "tool_id")
    private String toolId;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "grant_id")
    private String grantId;

    @Column(name = "action")
    private String action;

    @Column(name = "result_code")
    private String resultCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "request_digest", columnDefinition = "text")
    private String requestDigest;

    @Column(name = "response_digest", columnDefinition = "text")
    private String responseDigest;

    @Type(JsonType.class)
    @Column(name = "metadata", columnDefinition = "json")
    private Map<String, Object> metadata;
}

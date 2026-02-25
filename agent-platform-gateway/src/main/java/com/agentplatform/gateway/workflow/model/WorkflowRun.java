package com.agentplatform.gateway.workflow.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "workflow_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRun {

    @Id
    @Column(name = "run_id")
    private String runId;

    @Column(name = "graph_id", nullable = false)
    private String graphId;

    @Column(name = "graph_version", nullable = false)
    private Integer graphVersion;

    @Column(name = "actor_tid", nullable = false)
    private String actorTid;

    @Column(name = "trace_id")
    private String traceId;

    @Type(JsonType.class)
    @Column(name = "input", columnDefinition = "json")
    private Map<String, Object> input;

    @Type(JsonType.class)
    @Column(name = "output", columnDefinition = "json")
    private Map<String, Object> output;

    @Type(JsonType.class)
    @Column(name = "node_executions", columnDefinition = "json")
    private Map<String, Object> nodeExecutions;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_latency_ms")
    private Long totalLatencyMs;
}

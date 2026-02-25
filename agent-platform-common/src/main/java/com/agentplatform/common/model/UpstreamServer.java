package com.agentplatform.common.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "upstream_server")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamServer {

    @Id
    @Column(name = "server_id")
    private String serverId;

    @Column(name = "server_type", nullable = false)
    private String serverType; // mcp | rest

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "transport")
    private String transport; // streamable_http | sse | http | https

    @Type(JsonType.class)
    @Column(name = "auth_profile", columnDefinition = "json")
    private Map<String, Object> authProfile;

    @Type(JsonType.class)
    @Column(name = "api_spec", columnDefinition = "json")
    private Map<String, Object> apiSpec;

    @Column(name = "health_endpoint")
    private String healthEndpoint;

    @Column(name = "sse_endpoint")
    private String sseEndpoint;

    @Column(name = "owner_tid", nullable = false)
    private String ownerTid;

    @Column(name = "health_status")
    @Builder.Default
    private String healthStatus = "unknown";

    @Column(name = "last_health_check")
    private Instant lastHealthCheck;

    @Type(JsonType.class)
    @Column(name = "tags", columnDefinition = "json")
    private List<String> tags;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

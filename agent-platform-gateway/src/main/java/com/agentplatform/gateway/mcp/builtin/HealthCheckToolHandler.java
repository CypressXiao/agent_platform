package com.agentplatform.gateway.mcp.builtin;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.common.repository.UpstreamServerRepository;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Built-in tool to check health status of upstream servers.
 */
@Component
@RequiredArgsConstructor
public class HealthCheckToolHandler implements BuiltinToolHandler {

    private final UpstreamServerRepository serverRepo;

    @Override
    public String toolName() {
        return "health_check";
    }

    @Override
    public String description() {
        return "Check health status of registered upstream servers for the current tenant.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "server_id", Map.of("type", "string", "description", "Optional: specific server ID to check")
            )
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String serverId = (String) arguments.get("server_id");

        if (serverId != null) {
            return serverRepo.findById(serverId)
                .map(this::toStatusMap)
                .orElse(Map.of("error", "Server not found: " + serverId));
        }

        List<UpstreamServer> servers = serverRepo.findByOwnerTid(identity.tenantId());
        return Map.of(
            "servers", servers.stream().map(this::toStatusMap).collect(Collectors.toList()),
            "total", servers.size(),
            "healthy", servers.stream().filter(s -> "healthy".equals(s.getHealthStatus())).count()
        );
    }

    private Map<String, Object> toStatusMap(UpstreamServer server) {
        return Map.of(
            "server_id", server.getServerId(),
            "type", server.getServerType(),
            "status", server.getHealthStatus(),
            "last_check", server.getLastHealthCheck() != null ? server.getLastHealthCheck().toString() : "never"
        );
    }
}

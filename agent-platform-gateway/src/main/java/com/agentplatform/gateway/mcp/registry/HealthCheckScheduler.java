package com.agentplatform.gateway.mcp.registry;

import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.common.repository.UpstreamServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class HealthCheckScheduler {

    private final UpstreamServerRepository serverRepo;
    private final WebClient.Builder webClientBuilder;

    @Scheduled(fixedDelayString = "${mcp.gateway.health-check-interval:30000}")
    @Transactional
    public void checkAll() {
        List<UpstreamServer> servers = serverRepo.findAll();
        for (UpstreamServer server : servers) {
            checkHealth(server);
        }
    }

    private void checkHealth(UpstreamServer server) {
        String healthUrl = resolveHealthUrl(server);
        try {
            WebClient client = webClientBuilder.build();

            if ("mcp".equals(server.getServerType())) {
                // MCP servers: send ping via JSON-RPC
                Map<String, Object> pingRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", "health-check",
                    "method", "ping",
                    "params", Map.of()
                );
                client.post()
                    .uri(healthUrl)
                    .bodyValue(pingRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            } else {
                // REST servers: HTTP GET health endpoint
                client.get()
                    .uri(healthUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            }

            serverRepo.updateHealthStatus(server.getServerId(), "healthy", Instant.now());
        } catch (Exception e) {
            log.warn("Health check failed for server {}: {}", server.getServerId(), e.getMessage());
            serverRepo.updateHealthStatus(server.getServerId(), "unhealthy", Instant.now());
        }
    }

    private String resolveHealthUrl(UpstreamServer server) {
        if (server.getHealthEndpoint() != null && !server.getHealthEndpoint().isBlank()) {
            if (server.getHealthEndpoint().startsWith("http")) {
                return server.getHealthEndpoint();
            }
            return server.getBaseUrl() + server.getHealthEndpoint();
        }
        if ("mcp".equals(server.getServerType())) {
            return server.getBaseUrl() + "/mcp";
        }
        return server.getBaseUrl() + "/health";
    }
}

package com.agentplatform.gateway.mcp.registry;

import com.agentplatform.common.dto.RegisterMcpServerRequest;
import com.agentplatform.common.dto.RegisterRestApiRequest;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.common.repository.ToolRepository;
import com.agentplatform.common.repository.UpstreamServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstreamServerService {

    private final UpstreamServerRepository serverRepo;
    private final ToolRepository toolRepo;
    private final McpToolDiscoveryService mcpToolDiscovery;

    /**
     * Register an Upstream MCP Server and discover its tools.
     */
    @Transactional
    public UpstreamServer registerMcpServer(RegisterMcpServerRequest request) {
        UpstreamServer server = UpstreamServer.builder()
            .serverId(request.getServerId())
            .serverType("mcp")
            .baseUrl(request.getBaseUrl())
            .sseEndpoint(request.getSseEndpoint())
            .transport(request.getTransport())
            .authProfile(request.getAuthProfile())
            .ownerTid(request.getOwnerTid())
            .tags(request.getTags())
            .build();

        server = serverRepo.save(server);

        // Discover tools from the upstream MCP server
        try {
            List<Tool> discoveredTools = mcpToolDiscovery.discoverTools(server);
            toolRepo.saveAll(discoveredTools);
            log.info("Registered MCP server {} with {} tools", server.getServerId(), discoveredTools.size());
        } catch (Exception e) {
            log.warn("Tool discovery failed for MCP server {}: {}", server.getServerId(), e.getMessage());
        }

        return server;
    }

    /**
     * Register an Upstream REST API and create tool definitions.
     */
    @Transactional
    public UpstreamServer registerRestApi(RegisterRestApiRequest request) {
        UpstreamServer server = UpstreamServer.builder()
            .serverId(request.getServerId())
            .serverType("rest")
            .baseUrl(request.getBaseUrl())
            .authProfile(request.getAuthProfile())
            .apiSpec(request.getApiSpec())
            .healthEndpoint(request.getHealthEndpoint())
            .ownerTid(request.getOwnerTid())
            .tags(request.getTags())
            .build();

        server = serverRepo.save(server);

        // Create tool records from manual definitions
        if (request.getTools() != null) {
            List<Tool> tools = new ArrayList<>();
            for (RegisterRestApiRequest.RestToolDefinition def : request.getTools()) {
                Tool tool = Tool.builder()
                    .toolId(UUID.randomUUID().toString())
                    .toolName(def.getToolName())
                    .description(def.getDescription())
                    .sourceType("upstream_rest")
                    .sourceId(server.getServerId())
                    .ownerTid(request.getOwnerTid())
                    .inputSchema(def.getInputSchema())
                    .executionMapping(def.getExecutionMapping())
                    .responseMapping(def.getResponseMapping())
                    .build();
                tools.add(tool);
            }
            toolRepo.saveAll(tools);
            log.info("Registered REST API {} with {} tools", server.getServerId(), tools.size());
        }

        return server;
    }

    /**
     * Unregister a server and deactivate its tools.
     */
    @Transactional
    public void unregister(String serverId) {
        List<Tool> tools = toolRepo.findBySourceIdAndStatus(serverId, "active");
        tools.forEach(t -> {
            t.setStatus("disabled");
            t.setUpdatedAt(Instant.now());
        });
        toolRepo.saveAll(tools);
        serverRepo.deleteById(serverId);
        log.info("Unregistered server {} and disabled {} tools", serverId, tools.size());
    }

    public List<UpstreamServer> listByTenant(String ownerTid) {
        return serverRepo.findByOwnerTid(ownerTid);
    }

    public Optional<UpstreamServer> findById(String serverId) {
        return serverRepo.findById(serverId);
    }
}

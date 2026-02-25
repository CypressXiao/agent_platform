package com.agentplatform.gateway.mcp.upstream;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.common.repository.ToolRepository;
import com.agentplatform.common.repository.UpstreamServerRepository;
import com.agentplatform.gateway.mcp.registry.McpToolDiscoveryService;
import com.agentplatform.gateway.mcp.ToolVersionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 网关作为 MCP Client 管理与上游 MCP Server 的连接。
 * 
 * 功能：
 * 1. 与上游 MCP Server 建立 SSE 长连接
 * 2. 监听上游的 tools/list_changed 通知
 * 3. 自动同步上游工具到本地
 * 4. 级联推送变更通知给下游客户端
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UpstreamMcpClientManager {

    private final UpstreamServerRepository serverRepo;
    private final ToolRepository toolRepo;
    private final McpToolDiscoveryService toolDiscovery;
    private final ToolVersionService toolVersionService;
    private final WebClient.Builder webClientBuilder;
    private final StringRedisTemplate redisTemplate;

    private final ConcurrentHashMap<String, UpstreamConnection> connections = new ConcurrentHashMap<>();

    public record UpstreamConnection(
        String serverId,
        Disposable sseSubscription,
        Instant connectedAt,
        Instant lastEventAt
    ) {}

    @PostConstruct
    public void init() {
        // 启动时连接所有活跃的上游 MCP Server
        connectAllUpstreams();
    }

    @PreDestroy
    public void shutdown() {
        // 关闭所有连接
        connections.values().forEach(conn -> {
            if (conn.sseSubscription() != null && !conn.sseSubscription().isDisposed()) {
                conn.sseSubscription().dispose();
            }
        });
        connections.clear();
        log.info("Closed all upstream MCP connections");
    }

    /**
     * 连接所有活跃的上游 MCP Server
     */
    public void connectAllUpstreams() {
        List<UpstreamServer> mcpServers = serverRepo.findByServerTypeAndHealthStatus("mcp", "healthy");
        mcpServers.addAll(serverRepo.findByServerTypeAndHealthStatus("mcp", "unknown"));

        for (UpstreamServer server : mcpServers) {
            connectToUpstream(server);
        }
        log.info("Connected to {} upstream MCP servers", connections.size());
    }

    /**
     * 连接到单个上游 MCP Server
     */
    public void connectToUpstream(UpstreamServer server) {
        if (connections.containsKey(server.getServerId())) {
            log.debug("Already connected to upstream: {}", server.getServerId());
            return;
        }

        // Use configured sseEndpoint, or skip SSE if not provided
        String sseUrl = server.getSseEndpoint();
        if (sseUrl == null || sseUrl.isBlank()) {
            log.debug("No SSE endpoint configured for {}, skipping SSE connection", server.getServerId());
            return;
        }
        log.info("Connecting to upstream MCP server: {} at {}", server.getServerId(), sseUrl);

        try {
            WebClient client = webClientBuilder.build();

            Disposable subscription = client.get()
                .uri(sseUrl)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofMinutes(30))
                .doOnNext(event -> handleUpstreamEvent(server.getServerId(), event))
                .doOnError(e -> handleUpstreamError(server.getServerId(), e))
                .doOnComplete(() -> handleUpstreamDisconnect(server.getServerId()))
                .subscribe();

            connections.put(server.getServerId(), new UpstreamConnection(
                server.getServerId(),
                subscription,
                Instant.now(),
                Instant.now()
            ));

            // 初始同步工具
            syncToolsFromUpstream(server);

        } catch (Exception e) {
            log.warn("Failed to connect to upstream {}: {}", server.getServerId(), e.getMessage());
        }
    }

    /**
     * 断开与上游的连接
     */
    public void disconnectFromUpstream(String serverId) {
        UpstreamConnection conn = connections.remove(serverId);
        if (conn != null && conn.sseSubscription() != null) {
            conn.sseSubscription().dispose();
            log.info("Disconnected from upstream: {}", serverId);
        }
    }

    /**
     * 处理上游 SSE 事件
     */
    @SuppressWarnings("unchecked")
    private void handleUpstreamEvent(String serverId, ServerSentEvent<String> event) {
        // 更新最后事件时间
        UpstreamConnection existing = connections.get(serverId);
        if (existing != null) {
            connections.put(serverId, new UpstreamConnection(
                serverId,
                existing.sseSubscription(),
                existing.connectedAt(),
                Instant.now()
            ));
        }

        String eventType = event.event();
        String data = event.data();

        if (data == null || data.isBlank()) {
            return; // 心跳或空事件
        }

        log.debug("Received upstream event from {}: type={}", serverId, eventType);

        try {
            // 解析 JSON-RPC 消息
            Map<String, Object> message = parseJson(data);
            String method = (String) message.get("method");

            if ("notifications/tools/list_changed".equals(method)) {
                log.info("Upstream {} tools changed, syncing...", serverId);
                UpstreamServer server = serverRepo.findById(serverId).orElse(null);
                if (server != null) {
                    syncToolsFromUpstream(server);
                    // 递增版本号并级联通知下游客户端
                    toolVersionService.incrementVersion();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse upstream event: {}", e.getMessage());
        }
    }

    /**
     * 处理上游连接错误
     */
    private void handleUpstreamError(String serverId, Throwable error) {
        log.warn("Upstream connection error for {}: {}", serverId, error.getMessage());
        connections.remove(serverId);
        // 标记为不健康
        serverRepo.findById(serverId).ifPresent(server -> {
            server.setHealthStatus("unhealthy");
            serverRepo.save(server);
        });
    }

    /**
     * 处理上游断开连接
     */
    private void handleUpstreamDisconnect(String serverId) {
        log.info("Upstream {} disconnected", serverId);
        connections.remove(serverId);
    }

    /**
     * 从上游同步工具（幂等，带分布式锁和增量对比）
     */
    public void syncToolsFromUpstream(UpstreamServer server) {
        String lockKey = "mcp:sync_lock:" + server.getServerId();
        
        // 分布式锁，避免多实例重复同步
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Sync already in progress for {}, skipping", server.getServerId());
            return;
        }
        
        try {
            // 获取当前工具列表
            List<Tool> existingTools = toolRepo.findBySourceIdAndStatus(server.getServerId(), "active");
            Set<String> existingToolNames = existingTools.stream()
                .map(Tool::getToolName)
                .collect(Collectors.toSet());
            
            // 发现上游工具
            List<Tool> discoveredTools = toolDiscovery.discoverTools(server);
            Set<String> discoveredToolNames = discoveredTools.stream()
                .map(Tool::getToolName)
                .collect(Collectors.toSet());
            
            // 增量对比：只有真正变化时才更新
            if (existingToolNames.equals(discoveredToolNames)) {
                log.debug("No tool changes detected for {}", server.getServerId());
                return;
            }
            
            // 有变化，执行更新
            toolRepo.deleteBySourceId(server.getServerId());
            if (!discoveredTools.isEmpty()) {
                toolRepo.saveAll(discoveredTools);
            }
            
            log.info("Synced {} tools from upstream {} (changed from {})", 
                discoveredTools.size(), server.getServerId(), existingTools.size());
            
            // 只有真正变化时才通知下游
            toolVersionService.incrementVersion();
            
        } catch (Exception e) {
            log.warn("Failed to sync tools from {}: {}", server.getServerId(), e.getMessage());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 定时重连断开的上游
     */
    @Scheduled(fixedDelay = 60000) // 每分钟检查一次
    public void reconnectDisconnectedUpstreams() {
        List<UpstreamServer> mcpServers = serverRepo.findByServerTypeAndHealthStatus("mcp", "healthy");
        mcpServers.addAll(serverRepo.findByServerTypeAndHealthStatus("mcp", "unknown"));

        for (UpstreamServer server : mcpServers) {
            if (!connections.containsKey(server.getServerId())) {
                log.info("Reconnecting to upstream: {}", server.getServerId());
                connectToUpstream(server);
            }
        }
    }

    /**
     * 定时同步所有上游工具（兜底机制）
     */
    @Scheduled(fixedDelay = 300000) // 每5分钟全量同步一次
    public void periodicFullSync() {
        log.debug("Running periodic full sync of upstream tools");
        for (String serverId : connections.keySet()) {
            serverRepo.findById(serverId).ifPresent(this::syncToolsFromUpstream);
        }
    }

    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * 获取连接状态
     */
    public Map<String, UpstreamConnection> getConnections() {
        return Map.copyOf(connections);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}

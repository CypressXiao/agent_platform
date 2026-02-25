package com.agentplatform.gateway.mcp.sse;

import com.agentplatform.common.model.CallerIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 管理 SSE 会话。
 * 维护所有活跃的 SSE 连接，支持按租户广播通知。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpSseSessionManager {

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, SseSession> sessions = new ConcurrentHashMap<>();

    public record SseSession(
        String sessionId,
        CallerIdentity identity,
        Sinks.Many<ServerSentEvent<String>> sink
    ) {}

    /**
     * 创建新的 SSE 会话
     */
    public Sinks.Many<ServerSentEvent<String>> createSession(String sessionId, CallerIdentity identity) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessions.put(sessionId, new SseSession(sessionId, identity, sink));
        log.debug("Created SSE session: {}, total sessions: {}", sessionId, sessions.size());
        return sink;
    }

    /**
     * 获取会话的 Sink
     */
    public Sinks.Many<ServerSentEvent<String>> getSink(String sessionId) {
        SseSession session = sessions.get(sessionId);
        return session != null ? session.sink() : null;
    }

    /**
     * 移除会话
     */
    public void removeSession(String sessionId) {
        SseSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.sink().tryEmitComplete();
            log.debug("Removed SSE session: {}, remaining sessions: {}", sessionId, sessions.size());
        }
    }

    /**
     * 向所有会话广播 tools/list_changed 通知
     */
    public void broadcastToolsListChanged() {
        String notification = buildNotification("notifications/tools/list_changed", Map.of());
        broadcast(notification);
        log.info("Broadcasted tools/list_changed to {} sessions", sessions.size());
    }

    /**
     * 向指定租户的所有会话广播 tools/list_changed 通知
     */
    public void broadcastToolsListChangedToTenant(String tenantId) {
        String notification = buildNotification("notifications/tools/list_changed", Map.of());
        Set<String> targetSessions = sessions.values().stream()
            .filter(s -> tenantId.equals(s.identity().getTenantId()))
            .map(SseSession::sessionId)
            .collect(Collectors.toSet());

        for (String sessionId : targetSessions) {
            SseSession session = sessions.get(sessionId);
            if (session != null) {
                session.sink().tryEmitNext(ServerSentEvent.<String>builder()
                    .event("message")
                    .data(notification)
                    .build());
            }
        }
        log.info("Broadcasted tools/list_changed to {} sessions for tenant {}", targetSessions.size(), tenantId);
    }

    /**
     * 向所有会话广播消息
     */
    private void broadcast(String message) {
        for (SseSession session : sessions.values()) {
            session.sink().tryEmitNext(ServerSentEvent.<String>builder()
                .event("message")
                .data(message)
                .build());
        }
    }

    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    private String buildNotification(String method, Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params
            ));
        } catch (Exception e) {
            return "{}";
        }
    }
}

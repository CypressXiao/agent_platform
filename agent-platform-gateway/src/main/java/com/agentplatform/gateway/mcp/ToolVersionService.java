package com.agentplatform.gateway.mcp;

import com.agentplatform.gateway.mcp.sse.McpSseSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具版本管理服务。
 * 
 * 维护全局工具列表版本号，用于：
 * 1. SSE 模式：工具变更时推送通知
 * 2. HTTP 模式：客户端通过版本号判断是否需要刷新工具列表
 * 
 * 版本号存储在 Redis 中（支持多实例部署），本地缓存用于快速读取。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolVersionService {

    private final StringRedisTemplate redisTemplate;
    private final McpSseSessionManager sseSessionManager;

    private static final String TOOL_VERSION_KEY = "mcp:tool:version";
    private static final String TOOL_VERSION_CHANNEL = "mcp:tool:version:changed";

    // 本地缓存，避免每次都查 Redis
    private final AtomicLong localVersion = new AtomicLong(0);
    private volatile long lastSyncTime = 0;
    private static final long SYNC_INTERVAL_MS = 1000; // 每秒最多同步一次

    /**
     * 获取当前工具版本号
     */
    public long getCurrentVersion() {
        long now = System.currentTimeMillis();
        if (now - lastSyncTime > SYNC_INTERVAL_MS) {
            syncFromRedis();
        }
        return localVersion.get();
    }

    /**
     * 工具列表发生变更时调用，递增版本号并通知所有客户端
     */
    public long incrementVersion() {
        try {
            Long newVersion = redisTemplate.opsForValue().increment(TOOL_VERSION_KEY);
            if (newVersion != null) {
                localVersion.set(newVersion);
                lastSyncTime = System.currentTimeMillis();

                // 广播版本变更（通知其他网关实例）
                redisTemplate.convertAndSend(TOOL_VERSION_CHANNEL, String.valueOf(newVersion));

                // 推送 SSE 通知给所有连接的客户端
                sseSessionManager.broadcastToolsListChanged();

                log.info("Tool version incremented to: {}", newVersion);
                return newVersion;
            }
        } catch (Exception e) {
            log.warn("Failed to increment tool version in Redis: {}", e.getMessage());
        }

        // Redis 不可用时使用本地版本
        long fallback = localVersion.incrementAndGet();
        sseSessionManager.broadcastToolsListChanged();
        return fallback;
    }

    /**
     * 检查客户端的版本是否过期
     */
    public boolean isStale(long clientVersion) {
        return clientVersion < getCurrentVersion();
    }

    /**
     * 从 Redis 同步版本号
     */
    private void syncFromRedis() {
        try {
            String versionStr = redisTemplate.opsForValue().get(TOOL_VERSION_KEY);
            if (versionStr != null) {
                localVersion.set(Long.parseLong(versionStr));
            }
            lastSyncTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("Failed to sync tool version from Redis: {}", e.getMessage());
        }
    }

    /**
     * 处理来自其他实例的版本变更通知
     */
    public void onVersionChanged(long newVersion) {
        if (newVersion > localVersion.get()) {
            localVersion.set(newVersion);
            lastSyncTime = System.currentTimeMillis();
            // 推送给本实例连接的客户端
            sseSessionManager.broadcastToolsListChanged();
            log.debug("Tool version updated from cluster: {}", newVersion);
        }
    }
}

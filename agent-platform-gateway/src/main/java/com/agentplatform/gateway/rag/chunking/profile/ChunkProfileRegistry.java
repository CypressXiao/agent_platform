package com.agentplatform.gateway.rag.chunking.profile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ChunkProfile 注册中心
 * 管理所有业务线的 chunk profile
 */
@Component
@Slf4j
public class ChunkProfileRegistry {

    private final Map<String, ChunkProfile> profiles = new HashMap<>();
    private String defaultProfileName = "knowledge";

    private final List<ChunkProfile> autoRegisteredProfiles;

    public ChunkProfileRegistry(List<ChunkProfile> autoRegisteredProfiles) {
        this.autoRegisteredProfiles = autoRegisteredProfiles;
    }

    @PostConstruct
    public void init() {
        // 自动注册所有 Spring 管理的 ChunkProfile bean
        for (ChunkProfile profile : autoRegisteredProfiles) {
            register(profile);
        }
        log.info("ChunkProfileRegistry initialized with {} profiles: {}", 
            profiles.size(), profiles.keySet());
    }

    /**
     * 注册 profile
     */
    public void register(ChunkProfile profile) {
        profiles.put(profile.getName().toLowerCase(), profile);
        log.info("Registered ChunkProfile: {} - {}", profile.getName(), profile.getDescription());
    }

    /**
     * 获取 profile
     */
    public ChunkProfile get(String name) {
        if (name == null || name.isBlank()) {
            return getDefault();
        }
        ChunkProfile profile = profiles.get(name.toLowerCase());
        if (profile == null) {
            log.warn("ChunkProfile '{}' not found, falling back to default", name);
            return getDefault();
        }
        return profile;
    }

    /**
     * 获取默认 profile
     */
    public ChunkProfile getDefault() {
        ChunkProfile profile = profiles.get(defaultProfileName);
        if (profile == null) {
            throw new IllegalStateException("No default ChunkProfile registered: " + defaultProfileName);
        }
        return profile;
    }

    /**
     * 设置默认 profile
     */
    public void setDefaultProfile(String name) {
        if (!profiles.containsKey(name.toLowerCase())) {
            throw new IllegalArgumentException("Profile not registered: " + name);
        }
        this.defaultProfileName = name.toLowerCase();
        log.info("Default ChunkProfile set to: {}", name);
    }

    /**
     * 检查 profile 是否存在
     */
    public boolean exists(String name) {
        return profiles.containsKey(name.toLowerCase());
    }

    /**
     * 获取所有已注册的 profile 名称
     */
    public Set<String> getRegisteredProfiles() {
        return profiles.keySet();
    }

    /**
     * 获取 profile 信息（用于 API 返回）
     */
    public Map<String, Object> getProfileInfo(String name) {
        ChunkProfile profile = get(name);
        return Map.of(
            "name", profile.getName(),
            "description", profile.getDescription(),
            "collectionPattern", profile.getCollectionPattern(),
            "schemaFields", profile.getSchemaFields(),
            "chunkingConfig", Map.of(
                "strategy", profile.getChunkingConfig().getStrategy().name(),
                "chunkSize", profile.getChunkingConfig().getChunkSize(),
                "minChunkSize", profile.getChunkingConfig().getMinChunkSize(),
                "maxChunkSize", profile.getChunkingConfig().getMaxChunkSize()
            ),
            "isDefault", name.equalsIgnoreCase(defaultProfileName)
        );
    }
}

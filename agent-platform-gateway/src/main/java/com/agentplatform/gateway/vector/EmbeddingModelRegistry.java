package com.agentplatform.gateway.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Embedding 模型注册中心
 * 管理多个 embedding 模型，支持调用方动态选择
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class EmbeddingModelRegistry {

    /**
     * 模型注册表：模型名称 -> EmbeddingModel 实例
     */
    private final Map<String, EmbeddingModel> models = new HashMap<>();
    
    /**
     * 默认模型名称
     */
    private String defaultModelName = "default";

    /**
     * Spring AI 自动配置的 EmbeddingModel（如 OpenAI）
     */
    private final EmbeddingModel autoConfiguredModel;

    @Autowired
    public EmbeddingModelRegistry(@Autowired(required = false) EmbeddingModel autoConfiguredModel) {
        this.autoConfiguredModel = autoConfiguredModel;
    }

    @PostConstruct
    public void init() {
        // 注册自动配置的模型
        if (autoConfiguredModel != null) {
            register("default", autoConfiguredModel);
            register("openai", autoConfiguredModel);
            log.info("Registered auto-configured EmbeddingModel as 'default' and 'openai'");
        } else {
            log.warn("No EmbeddingModel auto-configured. Please register models manually.");
        }
    }

    /**
     * 注册 embedding 模型
     */
    public void register(String name, EmbeddingModel model) {
        models.put(name.toLowerCase(), model);
        log.info("Registered EmbeddingModel: {}", name);
    }

    /**
     * 获取 embedding 模型
     * @param name 模型名称，null 或空则返回默认模型
     */
    public EmbeddingModel get(String name) {
        if (name == null || name.isBlank()) {
            return getDefault();
        }
        
        EmbeddingModel model = models.get(name.toLowerCase());
        if (model == null) {
            log.warn("EmbeddingModel '{}' not found, falling back to default", name);
            return getDefault();
        }
        return model;
    }

    /**
     * 获取默认模型
     */
    public EmbeddingModel getDefault() {
        EmbeddingModel model = models.get(defaultModelName);
        if (model == null) {
            throw new IllegalStateException("No default EmbeddingModel registered");
        }
        return model;
    }

    /**
     * 设置默认模型
     */
    public void setDefaultModel(String name) {
        if (!models.containsKey(name.toLowerCase())) {
            throw new IllegalArgumentException("Model not registered: " + name);
        }
        this.defaultModelName = name.toLowerCase();
        log.info("Default EmbeddingModel set to: {}", name);
    }

    /**
     * 检查模型是否存在
     */
    public boolean exists(String name) {
        return models.containsKey(name.toLowerCase());
    }

    /**
     * 获取所有已注册的模型名称
     */
    public Set<String> getRegisteredModels() {
        return models.keySet();
    }

    /**
     * 获取模型信息（用于 API 返回）
     */
    public Map<String, Object> getModelInfo(String name) {
        EmbeddingModel model = get(name);
        if (model == null) {
            return Map.of("error", "Model not found: " + name);
        }
        
        // 获取模型维度（通过 embed 一个测试文本）
        int dimension = 0;
        try {
            float[] embedding = model.embed("test");
            dimension = embedding.length;
        } catch (Exception e) {
            log.warn("Failed to get embedding dimension for model {}: {}", name, e.getMessage());
        }
        
        return Map.of(
            "name", name,
            "dimension", dimension,
            "class", model.getClass().getSimpleName(),
            "isDefault", name.equalsIgnoreCase(defaultModelName)
        );
    }
}

package com.agentplatform.gateway.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Embedding 模型注册中心
 * 管理多个 embedding 模型，支持调用方动态选择
 * 优先级：enterprise（若启用）> Spring AI 自动配置模型
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

    /**
     * 默认 provider：enterprise | openai | ollama | azure
     */
    @Value("${agent-platform.vector.embedding.default-provider:enterprise}")
    private String defaultProvider;

    /** 企业模型开关 */
    @Value("${agent-platform.vector.embedding.enterprise.enabled:false}")
    private boolean enterpriseEnabled;

    @Value("${agent-platform.vector.embedding.enterprise.endpoint:}")
    private String enterpriseEndpoint;

    @Value("${agent-platform.vector.embedding.enterprise.api-key:}")
    private String enterpriseApiKey;

    @Value("${agent-platform.vector.embedding.enterprise.model:enterprise-embed-v1}")
    private String enterpriseModelName;

    @Value("${agent-platform.vector.embedding.enterprise.dimension:1536}")
    private int enterpriseDimension;

    @Value("${agent-platform.vector.embedding.enterprise.timeout-seconds:30}")
    private int enterpriseTimeout;

    @Value("${agent-platform.vector.embedding.enterprise.format:openai}")
    private String enterpriseFormat;

    @Autowired
    public EmbeddingModelRegistry(@Autowired(required = false) EmbeddingModel autoConfiguredModel) {
        this.autoConfiguredModel = autoConfiguredModel;
    }

    @PostConstruct
    public void init() {
        // 1. 注册企业内部模型（如果启用）
        if (enterpriseEnabled) {
            EnterpriseEmbeddingModel enterpriseModel = new EnterpriseEmbeddingModel(
                enterpriseEndpoint, enterpriseApiKey, enterpriseModelName,
                enterpriseDimension, enterpriseTimeout, enterpriseFormat
            );
            register("enterprise", enterpriseModel);
            log.info("Registered enterprise EmbeddingModel: endpoint={}, model={}, dimension={}",
                enterpriseEndpoint, enterpriseModelName, enterpriseDimension);
        }

        // 2. 注册 Spring AI 自动配置的模型
        if (autoConfiguredModel != null) {
            register("openai", autoConfiguredModel);
            log.info("Registered auto-configured EmbeddingModel as 'openai'");
        }

        // 3. 设置默认模型（按 default-provider 配置）
        if ("enterprise".equalsIgnoreCase(defaultProvider) && enterpriseEnabled) {
            register("default", models.get("enterprise"));
            log.info("Default EmbeddingModel set to: enterprise");
        } else if (autoConfiguredModel != null) {
            register("default", autoConfiguredModel);
            log.info("Default EmbeddingModel set to: openai (auto-configured)");
        } else if (enterpriseEnabled) {
            register("default", models.get("enterprise"));
            log.info("Default EmbeddingModel set to: enterprise (fallback, no auto-configured model)");
        } else {
            log.warn("No EmbeddingModel available. Please configure spring.ai.* or enterprise embedding.");
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

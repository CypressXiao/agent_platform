package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VectorStore 注册中心
 * 为每个 Embedding 模型创建独立的 VectorStore 实例（使用不同的 collection）
 * 
 * 架构：
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    VectorStoreRegistry                      │
 * ├─────────────────────────────────────────────────────────────┤
 * │  "bge-large-zh" → VectorStore (collection: vec_bge_large)   │
 * │  "openai"       → VectorStore (collection: vec_openai)      │
 * │  "bge-m3"       → VectorStore (collection: vec_bge_m3)      │
 * └─────────────────────────────────────────────────────────────┘
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorStoreRegistry {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    
    /**
     * VectorStore 注册表：模型名称 -> VectorStore 实例
     */
    private final Map<String, VectorStore> stores = new ConcurrentHashMap<>();
    
    /**
     * 模型维度映射
     */
    private final Map<String, Integer> modelDimensions = new ConcurrentHashMap<>();
    
    /**
     * 默认模型名称
     */
    private String defaultModelName = "default";
    
    /**
     * Collection 名称前缀
     */
    private static final String COLLECTION_PREFIX = "agent_platform_";

    public VectorStoreRegistry(MilvusServiceClient milvusClient, 
                                EmbeddingModelRegistry embeddingModelRegistry) {
        this.milvusClient = milvusClient;
        this.embeddingModelRegistry = embeddingModelRegistry;
        
        // 初始化常见模型的维度
        initModelDimensions();
    }

    private void initModelDimensions() {
        // OpenAI 模型
        modelDimensions.put("openai", 1536);
        modelDimensions.put("text-embedding-3-small", 1536);
        modelDimensions.put("text-embedding-3-large", 3072);
        modelDimensions.put("text-embedding-ada-002", 1536);
        
        // BGE 模型
        modelDimensions.put("bge-large-zh", 1024);
        modelDimensions.put("bge-base-zh", 768);
        modelDimensions.put("bge-small-zh", 512);
        modelDimensions.put("bge-m3", 1024);
        
        // 其他模型
        modelDimensions.put("m3e-base", 768);
        modelDimensions.put("m3e-large", 1024);
        modelDimensions.put("default", 1536);  // 默认使用 OpenAI 维度
        
        log.info("Initialized model dimensions for {} models", modelDimensions.size());
    }

    /**
     * 注册模型维度
     */
    public void registerModelDimension(String modelName, int dimension) {
        modelDimensions.put(modelName.toLowerCase(), dimension);
        log.info("Registered model dimension: {} -> {}", modelName, dimension);
    }

    /**
     * 获取或创建 VectorStore
     * 每个模型使用独立的 collection，确保 embedding 维度匹配
     */
    public VectorStore get(String modelName) {
        String name = (modelName == null || modelName.isBlank()) ? defaultModelName : modelName.toLowerCase();
        
        return stores.computeIfAbsent(name, this::createVectorStore);
    }

    /**
     * 获取默认 VectorStore
     */
    public VectorStore getDefault() {
        return get(defaultModelName);
    }

    /**
     * 创建 VectorStore
     */
    private VectorStore createVectorStore(String modelName) {
        // 获取 embedding 模型
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(modelName);
        
        // 获取模型维度
        int dimension = getModelDimension(modelName, embeddingModel);
        
        // 生成 collection 名称
        String collectionName = generateCollectionName(modelName);
        
        log.info("Creating VectorStore for model '{}' with collection '{}' and dimension {}", 
            modelName, collectionName, dimension);
        
        // 创建 MilvusVectorStore
        VectorStore vectorStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
            .collectionName(collectionName)
            .databaseName("default")
            .embeddingDimension(dimension)
            .build();
        
        log.info("Created VectorStore for model '{}'", modelName);
        return vectorStore;
    }

    /**
     * 获取模型维度
     */
    private int getModelDimension(String modelName, EmbeddingModel embeddingModel) {
        // 先从配置中查找
        Integer configuredDimension = modelDimensions.get(modelName.toLowerCase());
        if (configuredDimension != null) {
            return configuredDimension;
        }
        
        // 尝试通过 embedding 一个测试文本来获取维度
        try {
            float[] testEmbedding = embeddingModel.embed("test");
            int dimension = testEmbedding.length;
            modelDimensions.put(modelName.toLowerCase(), dimension);
            log.info("Detected dimension {} for model '{}'", dimension, modelName);
            return dimension;
        } catch (Exception e) {
            log.warn("Failed to detect dimension for model '{}', using default 1536: {}", 
                modelName, e.getMessage());
            return 1536;
        }
    }

    /**
     * 生成 collection 名称
     */
    private String generateCollectionName(String modelName) {
        // 将模型名称转换为合法的 collection 名称
        String sanitized = modelName.toLowerCase()
            .replaceAll("[^a-z0-9]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        
        return COLLECTION_PREFIX + sanitized;
    }

    /**
     * 设置默认模型
     */
    public void setDefaultModel(String modelName) {
        this.defaultModelName = modelName.toLowerCase();
        log.info("Default VectorStore model set to: {}", modelName);
    }

    /**
     * 检查模型是否已注册
     */
    public boolean exists(String modelName) {
        return stores.containsKey(modelName.toLowerCase());
    }

    /**
     * 获取所有已创建的 VectorStore 模型名称
     */
    public Set<String> getRegisteredModels() {
        return stores.keySet();
    }

    /**
     * 获取模型信息
     */
    public Map<String, Object> getModelInfo(String modelName) {
        String name = modelName.toLowerCase();
        boolean exists = stores.containsKey(name);
        Integer dimension = modelDimensions.get(name);
        
        return Map.of(
            "name", modelName,
            "vectorStoreCreated", exists,
            "dimension", dimension != null ? dimension : "unknown",
            "collectionName", generateCollectionName(name),
            "isDefault", name.equals(defaultModelName)
        );
    }

    /**
     * 预热：提前创建指定模型的 VectorStore
     */
    public void warmup(String... modelNames) {
        for (String modelName : modelNames) {
            try {
                get(modelName);
                log.info("Warmed up VectorStore for model '{}'", modelName);
            } catch (Exception e) {
                log.warn("Failed to warmup VectorStore for model '{}': {}", modelName, e.getMessage());
            }
        }
    }
}

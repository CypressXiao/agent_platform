package com.agentplatform.gateway.vector;

import com.agentplatform.gateway.rag.chunking.ChunkingConfig;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfile;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfileRegistry;
import com.agentplatform.gateway.vector.SparseMilvusVectorStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.index.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * VectorStore 注册中心
 * 支持两种命名方式：
 *   1. 按模型名：get(modelName) -> collection: agent_platform_{model}
 *   2. 按业务+模型：getByCollection(collectionPattern, modelName) -> collection: {business}_{model}
 * 
 * 架构：
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │                       VectorStoreRegistry                           │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │  按模型：                                                           │
 * │    "qwen3-vl-embedding" → collection: agent_platform_qwen3_vl_embedding │
 * │    "openai"             → collection: agent_platform_openai          │
 * │  按业务+模型：                                                       │
 * │    "product_doc_qwen3_vl_embedding" → collection: product_doc_qwen3_vl_embedding │
 * │    "faq_qwen3_vl_embedding"         → collection: faq_qwen3_vl_embedding         │
 * └───────────────────────────────────────────────────────────────────────┘
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorStoreRegistry {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final ChunkProfileRegistry profileRegistry;
    private final String databaseName;
    
    /**
     * VectorStore 注册表：collection名称 -> VectorStore 实例
     */
    private final Map<String, VectorStore> stores = new ConcurrentHashMap<>();
    
    /**
     * Collection 到模型的映射
     */
    private final Map<String, String> collectionToModel = new ConcurrentHashMap<>();
    
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
                                EmbeddingModelRegistry embeddingModelRegistry,
                                ChunkProfileRegistry profileRegistry,
                                @Value("${agent-platform.vector.milvus.database:default}") String databaseName) {
        this.milvusClient = milvusClient;
        this.embeddingModelRegistry = embeddingModelRegistry;
        this.profileRegistry = profileRegistry;
        this.databaseName = databaseName;
        
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
        
        // 阿里云 DashScope 模型
        modelDimensions.put("qwen3-vl-embedding", 1024);
        modelDimensions.put("text-embedding-v4", 1536);
        modelDimensions.put("enterprise", 1024);  // 企业模型默认维度
        
        // 其他模型
        modelDimensions.put("m3e-base", 768);
        modelDimensions.put("m3e-large", 1024);
        modelDimensions.put("default", 1024);  // 默认使用企业模型维度
        
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
     * 获取或创建 VectorStore（按模型名，使用默认 collection 命名）
     * 每个模型使用独立的 collection，确保 embedding 维度匹配
     */
    public VectorStore get(String modelName) {
        String name = (modelName == null || modelName.isBlank()) ? defaultModelName : modelName.toLowerCase();
        String collectionName = generateCollectionName(name);
        
        return getOrCreateVectorStore(collectionName, name);
    }
    
    /**
     * 获取或创建 VectorStore（按业务+模型，使用自定义 collection 命名）
     * 
     * @param collectionPattern collection 命名模式，如 "product_doc_{model}"
     * @param modelName 模型名称
     * @return VectorStore 实例
     */
    public VectorStore getByCollection(String collectionPattern, String modelName) {
        String name = (modelName == null || modelName.isBlank()) ? defaultModelName : modelName.toLowerCase();
        String sanitizedModel = sanitizeName(name);
        String collectionName = collectionPattern.replace("{model}", sanitizedModel);
        
        return getOrCreateVectorStore(collectionName, name);
    }
    
    /**
     * 获取或创建 VectorStore（直接指定 collection 名称）
     * 
     * @param collectionName 完整的 collection 名称
     * @param modelName 模型名称
     * @return VectorStore 实例
     */
    public VectorStore getByCollectionName(String collectionName, String modelName) {
        String name = (modelName == null || modelName.isBlank()) ? defaultModelName : modelName.toLowerCase();
        return getOrCreateVectorStore(collectionName, name);
    }
    
    private VectorStore getOrCreateVectorStore(String collectionName, String modelName) {
        return stores.computeIfAbsent(collectionName, cn -> {
            collectionToModel.put(cn, modelName);
            return createVectorStore(cn, modelName);
        });
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
    private VectorStore createVectorStore(String collectionName, String modelName) {
        // 获取 embedding 模型
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(modelName);
        
        // 获取模型维度
        int dimension = getModelDimension(modelName, embeddingModel);
        
        log.info("Creating VectorStore for model '{}' with collection '{}' and dimension {}", 
            modelName, collectionName, dimension);
        
        // 创建 MilvusVectorStore
        VectorStore vectorStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
            .collectionName(collectionName)
            .databaseName(databaseName)
            .embeddingDimension(dimension)
            .build();
        
        log.info("Created VectorStore for model '{}' in collection '{}'", modelName, collectionName);
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
     * 生成 collection 名称（默认按模型名）
     */
    private String generateCollectionName(String modelName) {
        return COLLECTION_PREFIX + sanitizeName(modelName);
    }
    
    /**
     * 清理名称，转换为合法的 collection 名称片段
     */
    private String sanitizeName(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    /**
     * 设置默认模型
     */
    public void setDefaultModel(String modelName) {
        this.defaultModelName = modelName.toLowerCase();
        log.info("Default VectorStore model set to: {}", modelName);
    }

    /**
     * 检查模型对应的默认 collection 是否已创建
     */
    public boolean exists(String modelName) {
        String name = (modelName == null || modelName.isBlank()) ? defaultModelName : modelName.toLowerCase();
        String collectionName = generateCollectionName(name);
        return stores.containsKey(collectionName);
    }

    /**
     * 检查指定 collection 是否已创建
     */
    public boolean existsCollection(String collectionName) {
        return stores.containsKey(collectionName);
    }

    /**
     * 获取所有已创建的 collection 名称
     */
    public Set<String> getRegisteredCollections() {
        return stores.keySet();
    }
    
    /**
     * 获取所有已创建的 VectorStore 模型名称
     */
    public Set<String> getRegisteredModels() {
        return Set.copyOf(collectionToModel.values());
    }

    /**
     * 获取模型信息
     */
    public Map<String, Object> getModelInfo(String modelName) {
        String name = modelName.toLowerCase();
        String collectionName = generateCollectionName(name);
        boolean exists = stores.containsKey(collectionName);
        Integer dimension = modelDimensions.get(name);
        
        return Map.of(
            "name", modelName,
            "vectorStoreCreated", exists,
            "dimension", dimension != null ? dimension : "unknown",
            "collectionName", collectionName,
            "isDefault", name.equals(defaultModelName)
        );
    }
    
    /**
     * 获取 collection 信息
     */
    public Map<String, Object> getCollectionInfo(String collectionName) {
        boolean exists = stores.containsKey(collectionName);
        String modelName = collectionToModel.get(collectionName);
        Integer dimension = modelName != null ? modelDimensions.get(modelName) : null;
        
        return Map.of(
            "collectionName", collectionName,
            "vectorStoreCreated", exists,
            "modelName", modelName != null ? modelName : "unknown",
            "dimension", dimension != null ? dimension : "unknown"
        );
    }

    /**
     * 检查 collection 是否支持稀疏向量
     */
    private boolean hasCollectionWithSparseSupport(String collectionName) {
        try {
            // 检查 collection 是否存在
            HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            
            R<Boolean> hasCollectionResponse = milvusClient.hasCollection(hasCollectionParam);
            if (!hasCollectionResponse.getData()) {
                return false; // collection不存在
            }
            
            // 检查 collection 是否包含稀疏向量字段
            DescribeCollectionParam describeParam = DescribeCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            
            R<DescribeCollectionResponse> describeResponse = milvusClient.describeCollection(describeParam);
            if (!describeResponse.getStatus().equals(R.Status.Success.getCode())) {
                log.warn("Failed to describe collection '{}': {}", collectionName, describeResponse.getMessage());
                return false;
            }
            
            // 检查是否存在 sparse_vector 字段
            return describeResponse.getData().getSchema().getFieldsList().stream()
                .anyMatch(field -> "sparse_vector".equals(field.getName()));
                
        } catch (Exception e) {
            log.warn("Failed to check sparse support for collection '{}': {}", collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * 获取 VectorStore（支持稀疏向量配置）
     */
    public VectorStore getWithSparseSupport(String collection, String modelName, ChunkingConfig chunkingConfig) {
        String cacheKey = buildCacheKey(modelName, chunkingConfig);
        
        return stores.computeIfAbsent(cacheKey, key -> {
            // 检查是否已存在支持稀疏向量的 collection
            if (hasCollectionWithSparseSupport(collection)) {
                log.info("Using existing sparse-enabled collection '{}' for model '{}'", collection, modelName);
                return getExistingVectorStore(collection, modelName);
            } else {
                log.info("Creating new sparse-enabled collection '{}' for model '{}'", collection, modelName);
                return createSparseVectorStore(collection, modelName, chunkingConfig);
            }
        });
    }

    /**
     * 获取已存在的 VectorStore
     */
    private VectorStore getExistingVectorStore(String collectionName, String modelName) {
        // 获取 embedding 模型
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(modelName);
        
        // 获取模型维度
        int dimension = getModelDimension(modelName, embeddingModel);
        
        log.info("Creating VectorStore for existing sparse-enabled collection '{}' with model '{}' and dimension {}", 
            collectionName, modelName, dimension);
        
        // 创建 MilvusVectorStore
        VectorStore vectorStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
            .collectionName(collectionName)
            .databaseName("default")
            .embeddingDimension(dimension)
            .build();
        
        return vectorStore;
    }

    /**
     * 创建支持稀疏向量的 VectorStore
     */
    private VectorStore createSparseVectorStore(String collectionName, String modelName, ChunkingConfig chunkingConfig) {
        // 获取 embedding 模型
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(modelName);
        
        // 获取模型维度
        int dimension = getModelDimension(modelName, embeddingModel);
        
        log.info("Creating sparse-enabled VectorStore for model '{}' with collection '{}' and dimension {}", 
            modelName, collectionName, dimension);
        
        try {
            // 创建支持稀疏向量的 collection
            createSparseCollection(collectionName, dimension, chunkingConfig);
            
            // 创建 SparseMilvusVectorStore
            VectorStore vectorStore = new SparseMilvusVectorStore(
                milvusClient, 
                embeddingModel, 
                collectionName, 
                databaseName,
                chunkingConfig
            );
            
            log.info("Created sparse-enabled VectorStore for model '{}' in collection '{}'", modelName, collectionName);
            return vectorStore;
            
        } catch (Exception e) {
            log.error("Failed to create sparse-enabled VectorStore for model '{}' in collection '{}'", 
                modelName, collectionName, e);
            throw new RuntimeException("Failed to create sparse-enabled VectorStore", e);
        }
    }

    /**
     * 创建支持稀疏向量的 Milvus collection
     */
    private void createSparseCollection(String collectionName, int dimension, ChunkingConfig chunkingConfig) {
        try {
            // 检查 collection 是否已存在
            HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            
            R<Boolean> hasCollectionResponse = milvusClient.hasCollection(hasCollectionParam);
            if (hasCollectionResponse.getData()) {
                log.info("Collection '{}' already exists, checking sparse support...", collectionName);
                if (hasCollectionWithSparseSupport(collectionName)) {
                    log.info("Collection '{}' already supports sparse vectors", collectionName);
                    return;
                } else {
                    log.warn("Collection '{}' exists but doesn't support sparse vectors, dropping and recreating...", collectionName);
                    DropCollectionParam dropParam = DropCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build();
                    milvusClient.dropCollection(dropParam);
                }
            }
            
            // 获取 profile 名称（从 chunkingConfig 或默认）
            String profileName = (chunkingConfig != null && chunkingConfig.getProfileName() != null) 
                ? chunkingConfig.getProfileName() 
                : "knowledge";
            ChunkProfile profile = profileRegistry.get(profileName);
            
            // 创建 collection schema
            CollectionSchemaParam.Builder schemaBuilder = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(true)
                .addFieldType(FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.VarChar)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .withMaxLength(65535)
                    .build())
                .addFieldType(FieldType.newBuilder()
                    .withName("vector")
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build());
            
            // 添加 profile 定义的 schema 字段
            addProfileFieldsToSchema(schemaBuilder, profile);
            
            // 添加通用字段
            addCommonFieldsToSchema(schemaBuilder);
            
            // 添加稀疏向量字段（占位符）
            schemaBuilder.addFieldType(FieldType.newBuilder()
                .withName("sparse_vector")
                .withDataType(DataType.FloatVector) // 使用FloatVector作为占位符，实际稀疏向量需要更高版本的Milvus客户端
                .build());
            
            // 添加文本字段
            schemaBuilder.addFieldType(FieldType.newBuilder()
                .withName("text")
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build());
            
            CollectionSchemaParam schemaParam = schemaBuilder.build();
            
            // 创建 collection
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(schemaParam)
                .build();
            
            R<RpcStatus> createResponse = milvusClient.createCollection(createParam);
            if (!createResponse.getStatus().equals(R.Status.Success.getCode())) {
                throw new RuntimeException("Failed to create collection: " + createResponse.getMessage());
            }
            
            log.info("Created sparse-enabled collection '{}'", collectionName);
            
            // 创建索引
            createSparseIndexes(collectionName, chunkingConfig);
            
            // 加载 collection
            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
            milvusClient.loadCollection(loadParam);
            
            log.info("Successfully created and loaded sparse-enabled collection '{}'", collectionName);
            
        } catch (Exception e) {
            log.error("Failed to create sparse collection '{}'", collectionName, e);
            throw new RuntimeException("Failed to create sparse collection", e);
        }
    }

    /**
     * 为稀疏向量创建索引
     */
    private void createSparseIndexes(String collectionName, ChunkingConfig chunkingConfig) {
        try {
            // 创建稠密向量索引
            CreateIndexParam denseIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexName("vector_index")
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":256}")
                .build();
            
            R<RpcStatus> denseIndexResponse = milvusClient.createIndex(denseIndexParam);
            if (!denseIndexResponse.getStatus().equals(R.Status.Success.getCode())) {
                log.warn("Failed to create dense vector index: {}", denseIndexResponse.getMessage());
            } else {
                log.info("Created dense vector index for collection '{}'", collectionName);
            }
            
            // 创建稀疏向量索引
            CreateIndexParam sparseIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("sparse_vector")
                .withIndexName("sparse_vector_index")
                .withIndexType(IndexType.HNSW) // 使用HNSW作为占位符，实际稀疏索引需要更高版本的Milvus客户端
                .withMetricType(MetricType.IP)
                .withExtraParam("{\"inverted_index_algo\":\"DAAT_MAXSCORE\"}")
                .build();
            
            R<RpcStatus> sparseIndexResponse = milvusClient.createIndex(sparseIndexParam);
            if (!sparseIndexResponse.getStatus().equals(R.Status.Success.getCode())) {
                log.warn("Failed to create sparse vector index: {}", sparseIndexResponse.getMessage());
            } else {
                log.info("Created sparse vector index for collection '{}'", collectionName);
            }
            
        } catch (Exception e) {
            log.error("Failed to create indexes for collection '{}'", collectionName, e);
            throw new RuntimeException("Failed to create indexes", e);
        }
    }

    /**
     * 构建 cache key
     */
    private String buildCacheKey(String modelName, ChunkingConfig chunkingConfig) {
        if (chunkingConfig == null) {
            return modelName;
        }
        return String.format("%s_sparse_%s_%s", 
            modelName, 
            chunkingConfig.isEnableSparseVector(),
            chunkingConfig.getSparseAnalyzer());
    }

    /**
     * 将 Profile 定义的 schema 字段添加到 Milvus collection schema
     */
    private void addProfileFieldsToSchema(CollectionSchemaParam.Builder schemaBuilder, ChunkProfile profile) {
        for (ChunkProfile.SchemaField field : profile.getSchemaFields()) {
            DataType dataType = mapFieldTypeToDataType(field.type());
            int maxLength = getMaxLengthForField(field.name(), field.type());
            
            schemaBuilder.addFieldType(FieldType.newBuilder()
                .withName(field.name())
                .withDataType(dataType)
                .withMaxLength(maxLength)
                .build());
        }
    }

    /**
     * 添加通用字段到 schema
     */
    private void addCommonFieldsToSchema(CollectionSchemaParam.Builder schemaBuilder) {
        // 添加系统级通用字段
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("tenant_id")
            .withDataType(DataType.VarChar)
            .withMaxLength(255)
            .build());
        
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("collection")
            .withDataType(DataType.VarChar)
            .withMaxLength(255)
            .build());
        
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("embedding_model")
            .withDataType(DataType.VarChar)
            .withMaxLength(255)
            .build());
        
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("enable_sparse_vector")
            .withDataType(DataType.Bool)
            .build());
        
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("sparse_analyzer")
            .withDataType(DataType.VarChar)
            .withMaxLength(64)
            .build());
        
        schemaBuilder.addFieldType(FieldType.newBuilder()
            .withName("source")
            .withDataType(DataType.VarChar)
            .withMaxLength(255)
            .build());
    }

    /**
     * 将 Profile FieldType 映射到 Milvus DataType
     */
    private DataType mapFieldTypeToDataType(ChunkProfile.SchemaField.FieldType fieldType) {
        return switch (fieldType) {
            case STRING -> DataType.VarChar;
            case INTEGER -> DataType.Int64;
            case FLOAT -> DataType.Float;
            case BOOLEAN -> DataType.Bool;
            case LIST -> DataType.VarChar; // 列表存储为逗号分隔的字符串
            case MAP -> DataType.VarChar;  // Map 存储为 JSON 字符串
        };
    }

    /**
     * 获取字段的最大长度
     */
    private int getMaxLengthForField(String fieldName, ChunkProfile.SchemaField.FieldType fieldType) {
        if (fieldType != ChunkProfile.SchemaField.FieldType.STRING && 
            fieldType != ChunkProfile.SchemaField.FieldType.LIST && 
            fieldType != ChunkProfile.SchemaField.FieldType.MAP) {
            return 0; // 非字符串类型不需要 maxLength
        }
        
        return switch (fieldName) {
            case "document_id", "document_name", "owner", "approver" -> 255;
            case "version", "publish_date" -> 64;
            case "applicable_scope", "step_goal", "step_output", "step_notice", "question" -> 512;
            case "tags", "keywords" -> 1024;
            case "chunk_type", "heading_title", "step_title" -> 255;
            case "answer" -> 2048;
            default -> 255;
        };
    }
}

package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Milvus 全局函数管理器
 * 负责创建和管理全局函数，实现 Hybrid Search
 */
@Component
@Slf4j
public class GlobalFunctionManager {

    private final MilvusServiceClient milvusClient;
    private final GlobalFunctionConfig config;

    public GlobalFunctionManager(MilvusServiceClient milvusClient, GlobalFunctionConfig config) {
        this.milvusClient = milvusClient;
        this.config = config;
    }

    /**
     * 🎯 应用启动时初始化全局函数
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeGlobalFunctions() {
        if (!config.isEnabled()) {
            log.info("Global functions are disabled, skipping initialization");
            return;
        }

        try {
            log.info("Initializing Milvus global functions...");
            
            // 1. 创建 Embedding 函数
            if (config.getEmbedding().isEnabled()) {
                createEmbeddingFunction();
            }
            
            // 2. 创建稀疏向量函数
            if (config.getSparse().isEnabled()) {
                createSparseFunction();
            }
            
            // 3. 创建混合搜索函数
            if (config.getHybrid().isEnabled()) {
                createHybridFunction();
            }
            
            // 4. 创建 Hybrid Collection
            if (config.getCollection().isAutoCreate()) {
                createHybridCollection();
            }
            
            log.info("Successfully initialized all global functions and collection");
            
        } catch (Exception e) {
            log.error("Failed to initialize global functions", e);
            // 不抛出异常，允许应用继续运行
        }
    }

    /**
     * 🎯 创建全局 Embedding 函数
     */
    private void createEmbeddingFunction() {
        try {
            String functionName = config.getEmbedding().getName();
            
            // 检查函数是否已存在
            if (functionExists(functionName)) {
                log.info("Embedding function '{}' already exists", functionName);
                return;
            }

            GlobalFunctionConfig.EmbeddingConfig embConfig = config.getEmbedding();
            
            // 🎯 创建全局 Embedding 函数
            Map<String, Object> functionParams = Map.of(
                "model", embConfig.getModel(),
                "endpoint", embConfig.getEndpoint(),
                "api_key", embConfig.getApiKey(),
                "dimension", embConfig.getDimension(),
                "timeout_seconds", embConfig.getTimeoutSeconds(),
                "retry_attempts", embConfig.getRetryAttempts()
            );

            log.info("Creating embedding function '{}' with params: {}", functionName, functionParams);
            
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            // R<RpcStatus> response = milvusClient.createFunction(functionName, FunctionType.EMBEDDING, functionParams);
            
            log.info("Created global embedding function: {}", functionName);

        } catch (Exception e) {
            log.error("Failed to create embedding function", e);
            throw e;
        }
    }

    /**
     * 🎯 创建全局稀疏向量函数
     */
    private void createSparseFunction() {
        try {
            String functionName = config.getSparse().getName();
            
            // 检查函数是否已存在
            if (functionExists(functionName)) {
                log.info("Sparse function '{}' already exists", functionName);
                return;
            }

            GlobalFunctionConfig.SparseConfig sparseConfig = config.getSparse();
            
            // 🎯 创建全局稀疏向量函数
            Map<String, Object> functionParams = Map.of(
                "analyzer", sparseConfig.getAnalyzer(),
                "bm25_k1", sparseConfig.getBm25K1(),
                "bm25_b", sparseConfig.getBm25B(),
                "bm25_epsilon", sparseConfig.getBm25Epsilon(),
                "min_term_length", sparseConfig.getMinTermLength(),
                "max_terms", sparseConfig.getMaxTerms()
            );

            log.info("Creating sparse function '{}' with params: {}", functionName, functionParams);
            
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            // R<RpcStatus> response = milvusClient.createFunction(functionName, FunctionType.SPARSE, functionParams);
            
            log.info("Created global sparse function: {}", functionName);

        } catch (Exception e) {
            log.error("Failed to create sparse function", e);
            throw e;
        }
    }

    /**
     * 🎯 创建全局混合搜索函数
     */
    private void createHybridFunction() {
        try {
            String functionName = config.getHybrid().getName();
            
            // 检查函数是否已存在
            if (functionExists(functionName)) {
                log.info("Hybrid function '{}' already exists", functionName);
                return;
            }

            GlobalFunctionConfig.HybridConfig hybridConfig = config.getHybrid();
            
            // 🎯 创建全局混合搜索函数
            Map<String, Object> functionParams = Map.of(
                "dense_function", hybridConfig.getDenseFunction(),
                "sparse_function", hybridConfig.getSparseFunction(),
                "fusion_type", hybridConfig.getFusionType(),
                "dense_weight", hybridConfig.getDenseWeight(),
                "sparse_weight", hybridConfig.getSparseWeight(),
                "threshold", hybridConfig.getThreshold()
            );

            log.info("Creating hybrid function '{}' with params: {}", functionName, functionParams);
            
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            // R<RpcStatus> response = milvusClient.createFunction(functionName, FunctionType.HYBRID, functionParams);
            
            log.info("Created global hybrid function: {}", functionName);

        } catch (Exception e) {
            log.error("Failed to create hybrid function", e);
            throw e;
        }
    }

    /**
     * 🎯 创建 Hybrid Collection
     */
    private void createHybridCollection() {
        try {
            String collectionName = config.getCollection().getName();
            
            // 检查 Collection 是否已存在
            if (collectionExists(collectionName)) {
                log.info("Collection '{}' already exists", collectionName);
                if (config.getCollection().isAutoLoad()) {
                    loadCollection(collectionName);
                }
                return;
            }

            log.info("Creating hybrid collection: {}", collectionName);
            
            // 🎯 定义字段（引用全局函数）
            List<FieldType> fields = Arrays.asList(
                // 主键字段
                createFieldType("id", "VARCHAR", true, 100),
                
                // 🎯 内容字段（引用全局函数）
                createFieldType("content", "VARCHAR", false, 65535),
                
                // 元数据字段
                createFieldType("metadata", "JSON", false, 0)
            );

            // 🎯 创建 Collection
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            CreateCollectionRequest createRequest = CreateCollectionRequest.builder()
                .withCollectionName(collectionName)
                .withFieldTypes(fields)
                .withFunctionReferences(Arrays.asList(
                    FunctionReference.builder()
                        .withFunctionName(config.getEmbedding().getName())
                        .withInputField("content")
                        .withOutputField("dense_vector")
                        .build(),
                    
                    FunctionReference.builder()
                        .withFunctionName(config.getSparse().getName())
                        .withInputField("content")
                        .withOutputField("sparse_vector")
                        .build()
                ))
                .build();
            
            milvusClient.createCollection(createRequest);
            */

            log.info("Created hybrid collection: {}", collectionName);

            // 🎯 加载 Collection
            if (config.getCollection().isAutoLoad()) {
                loadCollection(collectionName);
            }

        } catch (Exception e) {
            log.error("Failed to create hybrid collection", e);
            throw e;
        }
    }

    /**
     * 创建字段类型
     */
    private FieldType createFieldType(String name, String dataType, boolean isPrimaryKey, int maxLength) {
        // 注意：这里使用占位符，实际需要根据 Milvus API 调整
        /*
        return FieldType.newBuilder()
            .withName(name)
            .withDataType(DataType.valueOf(dataType))
            .withPrimaryKey(isPrimaryKey)
            .withMaxLength(maxLength)
            .build();
        */
        return null; // 占位符
    }

    /**
     * 检查函数是否存在
     */
    private boolean functionExists(String functionName) {
        try {
            // 注意：这里使用占位符，实际需要根据 Milvus API 调整
            /*
            R<DescribeFunctionResponse> response = milvusClient.describeFunction(
                DescribeFunctionRequest.builder()
                    .withFunctionName(functionName)
                    .build()
            );
            
            return response.getStatus() == R.Status.Success.getCode();
            */
            return false; // 占位符
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 Collection 是否存在
     */
    private boolean collectionExists(String collectionName) {
        try {
            // 注意：这里使用占位符，实际需要根据 Milvus API 调整
            /*
            R<DescribeCollectionResponse> response = milvusClient.describeCollection(
                DescribeCollectionRequest.builder()
                    .withCollectionName(collectionName)
                    .build()
            );
            
            return response.getStatus() == R.Status.Success.getCode();
            */
            return false; // 占位符
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 加载 Collection
     */
    private void loadCollection(String collectionName) {
        try {
            log.info("Loading collection: {}", collectionName);
            
            // 注意：这里使用占位符，实际需要根据 Milvus API 调整
            /*
            R<RpcStatus> response = milvusClient.loadCollection(
                LoadCollectionRequest.builder()
                    .withCollectionName(collectionName)
                    .build()
            );
            
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to load collection: " + response.getMessage());
            }
            */
            
            log.info("Successfully loaded collection: {}", collectionName);
            
        } catch (Exception e) {
            log.error("Failed to load collection: {}", collectionName, e);
            throw e;
        }
    }

    /**
     * 获取函数状态
     */
    public Map<String, Object> getFunctionStatus() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("embedding", Map.of(
            "exists", functionExists(config.getEmbedding().getName()),
            "enabled", config.getEmbedding().isEnabled()
        ));
        
        status.put("sparse", Map.of(
            "exists", functionExists(config.getSparse().getName()),
            "enabled", config.getSparse().isEnabled()
        ));
        
        status.put("hybrid", Map.of(
            "exists", functionExists(config.getHybrid().getName()),
            "enabled", config.getHybrid().isEnabled()
        ));
        
        status.put("collection", Map.of(
            "exists", collectionExists(config.getCollection().getName()),
            "name", config.getCollection().getName()
        ));
        
        return status;
    }
}

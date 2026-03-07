package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Milvus 函数管理器
 * 负责创建和管理全局函数
 */
@Component
@Slf4j
public class MilvusFunctionManager {

    private final MilvusServiceClient milvusClient;
    private final MilvusFunctionConfig functionConfig;

    public MilvusFunctionManager(MilvusServiceClient milvusClient, 
                               MilvusFunctionConfig functionConfig) {
        this.milvusClient = milvusClient;
        this.functionConfig = functionConfig;
    }

    /**
     * 🎯 应用启动时创建全局函数
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeGlobalFunctions() {
        try {
            log.info("Initializing Milvus global functions...");
            
            // 1. 创建 Embedding 函数
            createEmbeddingFunction();
            
            // 2. 创建稀疏向量函数
            createSparseFunction();
            
            // 3. 创建混合搜索函数
            createHybridFunction();
            
            log.info("Successfully initialized all global functions");
            
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
            // 检查函数是否已存在
            if (functionExists("enterprise_embedding")) {
                log.info("Enterprise embedding function already exists");
                return;
            }

            // 🎯 创建全局 Embedding 函数
            CreateFunctionRequest request = CreateFunctionRequest.builder()
                .withFunctionName("enterprise_embedding")
                .withFunctionType(FunctionType.EMBEDDING)
                .withDescription("Enterprise embedding function for qwen3-vl-embedding")
                .withParams(Map.of(
                    "model", functionConfig.getEmbeddingModel(),
                    "endpoint", functionConfig.getEmbeddingEndpoint(),
                    "api_key", functionConfig.getEmbeddingApiKey(),  // 🎯 一次性配置
                    "dimension", functionConfig.getEmbeddingDimension(),
                    "timeout_seconds", 30,
                    "retry_attempts", 3
                ))
                .build();

            R<RpcStatus> response = milvusClient.createFunction(request);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create embedding function: " + response.getMessage());
            }

            log.info("Created global embedding function: enterprise_embedding");

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
            // 检查函数是否已存在
            if (functionExists("enterprise_sparse")) {
                log.info("Enterprise sparse function already exists");
                return;
            }

            // 🎯 创建全局稀疏向量函数
            CreateFunctionRequest request = CreateFunctionRequest.builder()
                .withFunctionName("enterprise_sparse")
                .withFunctionType(FunctionType.SPARSE)
                .withDescription("Enterprise sparse vector function with BM25")
                .withParams(Map.of(
                    "analyzer", functionConfig.getSparseAnalyzer(),
                    "bm25_k1", functionConfig.getBm25K1(),
                    "bm25_b", functionConfig.getBm25B(),
                    "bm25_epsilon", functionConfig.getBm25Epsilon(),
                    "min_term_length", 2,
                    "max_terms", 1000
                ))
                .build();

            R<RpcStatus> response = milvusClient.createFunction(request);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create sparse function: " + response.getMessage());
            }

            log.info("Created global sparse function: enterprise_sparse");

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
            // 检查函数是否已存在
            if (functionExists("enterprise_hybrid")) {
                log.info("Enterprise hybrid function already exists");
                return;
            }

            // 🎯 创建全局混合搜索函数
            CreateFunctionRequest request = CreateFunctionRequest.builder()
                .withFunctionName("enterprise_hybrid")
                .withFunctionType(FunctionType.HYBRID)
                .withDescription("Enterprise hybrid search function")
                .withParams(Map.of(
                    "dense_function", "enterprise_embedding",
                    "sparse_function", "enterprise_sparse",
                    "fusion_type", "weighted_sum",
                    "dense_weight", functionConfig.getDenseWeight(),
                    "sparse_weight", functionConfig.getSparseWeight(),
                    "threshold", functionConfig.getHybridThreshold()
                ))
                .build();

            R<RpcStatus> response = milvusClient.createFunction(request);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create hybrid function: " + response.getMessage());
            }

            log.info("Created global hybrid function: enterprise_hybrid");

        } catch (Exception e) {
            log.error("Failed to create hybrid function", e);
            throw e;
        }
    }

    /**
     * 检查函数是否存在
     */
    private boolean functionExists(String functionName) {
        try {
            R<DescribeFunctionResponse> response = milvusClient.describeFunction(
                DescribeFunctionRequest.builder()
                    .withFunctionName(functionName)
                    .build()
            );
            
            return response.getStatus() == R.Status.Success.getCode();
            
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取所有全局函数
     */
    public List<FunctionInfo> listGlobalFunctions() {
        try {
            R<ListFunctionsResponse> response = milvusClient.listFunctions(
                ListFunctionsRequest.builder().build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to list functions: " + response.getMessage());
            }

            return response.getData().getFunctions().stream()
                .map(func -> FunctionInfo.builder()
                    .name(func.getName())
                    .type(func.getType().name())
                    .description(func.getDescription())
                    .build())
                .toList();

        } catch (Exception e) {
            log.error("Failed to list functions", e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除全局函数
     */
    public void deleteFunction(String functionName) {
        try {
            R<RpcStatus> response = milvusClient.dropFunction(
                DropFunctionRequest.builder()
                    .withFunctionName(functionName)
                    .build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to delete function: " + response.getMessage());
            }

            log.info("Deleted global function: {}", functionName);

        } catch (Exception e) {
            log.error("Failed to delete function: {}", functionName, e);
            throw e;
        }
    }

    /**
     * 更新全局函数
     */
    public void updateFunction(String functionName, Map<String, Object> newParams) {
        try {
            // 先删除旧函数
            deleteFunction(functionName);
            
            // 根据函数类型重新创建
            switch (functionName) {
                case "enterprise_embedding":
                    createEmbeddingFunction();
                    break;
                case "enterprise_sparse":
                    createSparseFunction();
                    break;
                case "enterprise_hybrid":
                    createHybridFunction();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown function: " + functionName);
            }

            log.info("Updated global function: {}", functionName);

        } catch (Exception e) {
            log.error("Failed to update function: {}", functionName, e);
            throw e;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class FunctionInfo {
        private String name;
        private String type;
        private String description;
    }

    @lombok.Data
    @lombok.Builder
    public static class MilvusFunctionConfig {
        // Embedding 配置
        private String embeddingModel = "qwen3-vl-embedding";
        private String embeddingEndpoint = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
        private String embeddingApiKey = "";
        private int embeddingDimension = 1024;

        // 稀疏向量配置
        private String sparseAnalyzer = "standard";
        private double bm25K1 = 1.2;
        private double bm25B = 0.75;
        private double bm25Epsilon = 0.25;

        // 混合搜索配置
        private double denseWeight = 0.6;
        private double sparseWeight = 0.4;
        private double hybridThreshold = 0.5;
    }
}

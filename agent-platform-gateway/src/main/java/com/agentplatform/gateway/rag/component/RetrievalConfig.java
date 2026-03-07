package com.agentplatform.gateway.rag.component;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索配置策略
 * 根据 reranker 类型和场景动态调整 topK 和阈值
 */
@Component
@Data
@ConfigurationProperties(prefix = "agent-platform.rag.retrieval")
public class RetrievalConfig {

    /**
     * 默认检索配置
     */
    private DefaultConfig defaultConfig = new DefaultConfig();

    /**
     * 按文档类型的配置
     */
    private Map<String, DocumentTypeConfig> documentTypes = new HashMap<>();

    /**
     * 按 reranker 类型的配置
     */
    private Map<String, RerankerConfig> rerankerConfigs = new HashMap<>();

    @Data
    public static class DefaultConfig {
        /**
         * 无 rerank 时的 topK
         */
        private int topK = 5;

        /**
         * 有 rerank 时的检索倍数
         */
        private double rerankRetrieveMultiplier = 3.0;

        /**
         * 默认相似度阈值
         */
        private double similarityThreshold = 0.7;

        /**
         * 有 rerank 时的阈值降低比例
         */
        private double rerankThresholdReduction = 0.2;
    }

    @Data
    public static class DocumentTypeConfig {
        /**
         * 文档类型基础 topK
         */
        private int baseTopK = 5;

        /**
         * rerank 检索倍数
         */
        private double rerankMultiplier = 3.0;

        /**
         * 相似度阈值
         */
        private double similarityThreshold = 0.7;

        /**
         * rerank 阈值降低比例
         */
        private double rerankThresholdReduction = 0.2;

        /**
         * 最大检索数量（成本控制）
         */
        private int maxRetrieveK = 50;

        /**
         * 最小检索数量（质量保证）
         */
        private int minRetrieveK = 10;
    }

    @Data
    public static class RerankerConfig {
        /**
         * reranker 类型的检索倍数
         */
        private double retrieveMultiplier = 3.0;

        /**
         * 阈值降低比例
         */
        private double thresholdReduction = 0.2;

        /**
         * 最大检索数量（API 成本控制）
         */
        private int maxRetrieveK = 100;

        /**
         * 推荐的 topK 范围
         */
        private int minTopK = 3;
        private int maxTopK = 20;

        /**
         * 成本敏感度（0-1，越高越敏感）
         */
        private double costSensitivity = 0.5;
    }

    /**
     * 计算最优的检索配置
     */
    public RetrievalParams calculateOptimalParams(RetrievalRequest request) {
        String documentType = request.getDocumentType();
        String rerankerType = request.getRerankerType();
        int userTopK = request.getTopK();

        // 1. 获取文档类型配置
        DocumentTypeConfig docConfig = documentTypes.getOrDefault(documentType, new DocumentTypeConfig());

        // 2. 获取 reranker 配置
        RerankerConfig rerankConfig = rerankerConfigs.getOrDefault(rerankerType, new RerankerConfig());

        // 3. 计算检索 topK
        int retrieveK = calculateRetrieveK(userTopK, docConfig, rerankConfig);

        // 4. 计算相似度阈值
        double similarityThreshold = calculateSimilarityThreshold(docConfig, rerankConfig);

        return RetrievalParams.builder()
            .retrieveK(retrieveK)
            .finalTopK(userTopK)
            .similarityThreshold(similarityThreshold)
            .costOptimized(isCostOptimized(rerankConfig))
            .build();
    }

    private int calculateRetrieveK(int userTopK, DocumentTypeConfig docConfig, RerankerConfig rerankConfig) {
        // 基础计算
        double multiplier = Math.max(docConfig.getRerankMultiplier(), rerankConfig.getRetrieveMultiplier());
        int retrieveK = (int) Math.ceil(userTopK * multiplier);

        // 边界控制
        retrieveK = Math.max(retrieveK, docConfig.getMinRetrieveK());
        retrieveK = Math.min(retrieveK, Math.min(docConfig.getMaxRetrieveK(), rerankConfig.getMaxRetrieveK()));

        return retrieveK;
    }

    private double calculateSimilarityThreshold(DocumentTypeConfig docConfig, RerankerConfig rerankConfig) {
        double baseThreshold = docConfig.getSimilarityThreshold();
        double reduction = Math.max(docConfig.getRerankThresholdReduction(), rerankConfig.getThresholdReduction());
        
        return baseThreshold * (1.0 - reduction);
    }

    private boolean isCostOptimized(RerankerConfig rerankConfig) {
        return rerankConfig.getCostSensitivity() > 0.7;
    }

    @lombok.Data
    @lombok.Builder
    public static class RetrievalRequest {
        private String documentType;
        private String rerankerType;
        private int topK;
        private boolean enableRerank;
    }

    @lombok.Data
    @lombok.Builder
    public static class RetrievalParams {
        private int retrieveK;
        private int finalTopK;
        private double similarityThreshold;
        private boolean costOptimized;
    }
}

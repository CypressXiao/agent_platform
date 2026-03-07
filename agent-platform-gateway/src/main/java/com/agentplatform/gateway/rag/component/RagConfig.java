package com.agentplatform.gateway.rag.component;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 配置 - 明确区分原生配置和优化策略
 */
@Component
@Data
@ConfigurationProperties(prefix = "agent-platform.rag")
public class RagConfig {

    /**
     * Retrieval 原生配置（VectorStore 检索参数）
     */
    private RetrievalNativeConfig retrieval = new RetrievalNativeConfig();

    /**
     * Rerank 原生配置（Reranker API 参数）
     */
    private RerankNativeConfig rerank = new RerankNativeConfig();

    /**
     * 应用层优化策略（我们的智能调节逻辑）
     */
    private OptimizationStrategy optimization = new OptimizationStrategy();

    /**
     * Retrieval 原生配置
     */
    @Data
    public static class RetrievalNativeConfig {
        /**
         * 默认 topK（VectorStore 原生参数）
         */
        private int defaultTopK = 5;

        /**
         * 默认相似度阈值（VectorStore 原生参数）
         */
        private double defaultSimilarityThreshold = 0.7;

        /**
         * 支持的 embedding 模型
         */
        private Map<String, ModelConfig> embeddingModels = new HashMap<>();

        /**
         * 混合检索配置
         */
        private HybridSearchConfig hybridSearch = new HybridSearchConfig();
    }

    /**
     * Rerank 原生配置
     */
    @Data
    public static class RerankNativeConfig {
        /**
         * 默认启用状态
         */
        private boolean enabled = true;

        /**
         * 默认 provider
         */
        private String defaultProvider = "local";

        /**
         * 各 provider 的原生配置
         */
        private Map<String, ProviderNativeConfig> providers = new HashMap<>();
    }

    /**
     * Provider 原生配置
     */
    @Data
    public static class ProviderNativeConfig {
        /**
         * API 地址（原生参数）
         */
        private String apiUrl;

        /**
         * API 密钥（原生参数）
         */
        private String apiKey;

        /**
         * 默认模型（原生参数）
         */
        private String model;

        /**
         * 默认 top_n（原生参数）
         */
        private int topN = 10;

        /**
         * API 特定格式（原生参数）
         */
        private String format = "openai"; // openai, dashscope, cohere

        /**
         * 超时时间（原生参数）
         */
        private int timeoutSeconds = 30;

        /**
         * 重试配置（原生参数）
         */
        private RetryConfig retry = new RetryConfig();
    }

    /**
     * 应用层优化策略
     */
    @Data
    public static class OptimizationStrategy {
        /**
         * 智能调节开关
         */
        private boolean enabled = true;

        /**
         * 按文档类型的检索策略
         */
        private Map<String, DocumentTypeStrategy> documentTypes = new HashMap<>();

        /**
         * 按场景的优化策略
         */
        private Map<String, ScenarioStrategy> scenarios = new HashMap<>();

        /**
         * 成本控制策略
         */
        private CostControlStrategy costControl = new CostControlStrategy();
    }

    @Data
    public static class ModelConfig {
        private String name;
        private int dimension;
        private double maxDistance;
    }

    @Data
    public static class HybridSearchConfig {
        private boolean enabled = false;
        private String sparseAnalyzer = "standard";
        private double denseWeight = 0.7;
        private double sparseWeight = 0.3;
    }

    @Data
    public static class RetryConfig {
        private int maxAttempts = 3;
        private long delayMs = 500;
        private double backoffMultiplier = 2.0;
    }

    @Data
    public static class DocumentTypeStrategy {
        /**
         * 检索 topK 调节因子（相对于用户请求）
         */
        private double retrieveMultiplier = 1.0;

        /**
         * 相似度阈值调节
         */
        private double thresholdAdjustment = 0.0;

        /**
         * 是否启用 rerank
         */
        private boolean enableRerank = true;

        /**
         * rerank topK 调节
         */
        private double rerankMultiplier = 1.0;
    }

    @Data
    public static class ScenarioStrategy {
        private String name;
        private String description;
        private DocumentTypeStrategy strategy;
    }

    @Data
    public static class CostControlStrategy {
        /**
         * 成本敏感度（0-1）
         */
        private double sensitivity = 0.5;

        /**
         * 最大检索数量（硬限制）
         */
        private int maxRetrieveK = 100;

        /**
         * 最大 rerank 数量（硬限制）
         */
        private int maxRerankK = 50;

        /**
         * 成本预算（tokens/小时）
         */
        private long tokenBudgetPerHour = 100000;
    }
}

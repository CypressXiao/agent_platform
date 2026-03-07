package com.agentplatform.gateway.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局函数配置
 */
@Component
@Data
@ConfigurationProperties(prefix = "agent-platform.rag.milvus.functions")
public class GlobalFunctionConfig {

    private boolean enabled = true;
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private SparseConfig sparse = new SparseConfig();
    private HybridConfig hybrid = new HybridConfig();
    private CollectionConfig collection = new CollectionConfig();

    @Data
    public static class EmbeddingConfig {
        private boolean enabled = true;
        private String name = "enterprise_embedding";
        private String model = "qwen3-vl-embedding";
        private String endpoint = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
        private String apiKey = "";
        private int dimension = 1024;
        private int timeoutSeconds = 30;
        private int retryAttempts = 3;
        private String description = "企业内部 embedding 函数";
    }

    @Data
    public static class SparseConfig {
        private boolean enabled = true;
        private String name = "enterprise_sparse";
        private String analyzer = "standard";
        private double bm25K1 = 1.2;
        private double bm25B = 0.75;
        private double bm25Epsilon = 0.25;
        private int minTermLength = 2;
        private int maxTerms = 1000;
        private String description = "企业内部稀疏向量函数";
    }

    @Data
    public static class HybridConfig {
        private boolean enabled = true;
        private String name = "enterprise_hybrid";
        private String denseFunction = "enterprise_embedding";
        private String sparseFunction = "enterprise_sparse";
        private String fusionType = "weighted_sum";
        private double denseWeight = 0.6;
        private double sparseWeight = 0.4;
        private double threshold = 0.5;
        private String description = "企业内部混合搜索函数";
    }

    @Data
    public static class CollectionConfig {
        private String name = "agent_platform_hybrid";
        private boolean autoCreate = true;
        private boolean autoLoad = true;
    }
}

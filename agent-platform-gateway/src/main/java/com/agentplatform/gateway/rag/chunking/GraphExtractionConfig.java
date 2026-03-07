package com.agentplatform.gateway.rag.chunking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图关系提取配置
 */
@Component
@Data
@ConfigurationProperties(prefix = "agent-platform.rag.graph-extraction")
public class GraphExtractionConfig {

    /**
     * 是否启用异步处理
     */
    private boolean asyncEnabled = true;

    /**
     * LLM 实体提取配置
     */
    private LlmExtraction llmExtraction = new LlmExtraction();

    /**
     * LLM 关系分析配置
     */
    private LlmRelation llmRelation = new LlmRelation();

    /**
     * 性能配置
     */
    private Performance performance = new Performance();

    @Data
    public static class LlmExtraction {
        /**
         * 使用的模型
         */
        private String model = "default";

        /**
         * 温度参数
         */
        private double temperature = 0.1;

        /**
         * 最大输出长度
         */
        private int maxTokens = 300;

        /**
         * 处理的最大分块数
         */
        private int maxChunks = 8;

        /**
         * 每个分块的最大字符数
         */
        private int maxChunkChars = 500;
    }

    @Data
    public static class LlmRelation {
        /**
         * 使用的模型
         */
        private String model = "default";

        /**
         * 温度参数
         */
        private double temperature = 0.3;

        /**
         * 最大输出长度
         */
        private int maxTokens = 500;

        /**
         * 处理的最大分块数
         */
        private int maxChunks = 5;

        /**
         * 每个分块的最大字符数
         */
        private int maxChunkChars = 300;

        /**
         * 最小实体数量（低于此数量不进行关系分析）
         */
        private int minEntities = 2;
    }

    @Data
    public static class Performance {
        /**
         * 异步线程池大小
         */
        private int threadPoolSize = 2;

        /**
         * 异步队列容量
         */
        private int queueCapacity = 100;

        /**
         * 是否启用批量处理
         */
        private boolean batchEnabled = true;

        /**
         * 批量处理大小
         */
        private int batchSize = 10;
    }
}

package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Hybrid Function 配置
 */
@Configuration
@Import({GlobalFunctionConfig.class})
@ConditionalOnProperty(name = "agent-platform.rag.milvus.functions.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class HybridFunctionConfiguration {

    @Bean
    public GlobalFunctionManager globalFunctionManager(
            MilvusServiceClient milvusClient,
            GlobalFunctionConfig config) {
        log.info("Creating GlobalFunctionManager with functions enabled: {}", config.isEnabled());
        return new GlobalFunctionManager(milvusClient, config);
    }

    @Bean
    public HybridFunctionVectorStore hybridFunctionVectorStore(
            MilvusServiceClient milvusClient,
            GlobalFunctionConfig config) {
        log.info("Creating HybridFunctionVectorStore for collection: {}", config.getCollection().getName());
        return new HybridFunctionVectorStore(milvusClient, config);
    }

    @Bean
    @ConditionalOnProperty(name = "agent-platform.rag.milvus.functions.fallback.enabled", havingValue = "true")
    public HybridFunctionFallbackService hybridFunctionFallbackService(
            EmbeddingModel embeddingModel,
            GlobalFunctionConfig config) {
        log.info("Creating HybridFunctionFallbackService for fallback scenarios");
        return new HybridFunctionFallbackService(embeddingModel, config);
    }
}

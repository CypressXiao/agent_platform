package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorStoreConfig {

    @Value("${agent-platform.vector.milvus.host:localhost}")
    private String milvusHost;

    @Value("${agent-platform.vector.milvus.port:19530}")
    private int milvusPort;

    @Value("${agent-platform.vector.milvus.collection:agent_platform_vectors}")
    private String collectionName;

    @Bean
    public MilvusServiceClient milvusClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
            .withHost(milvusHost)
            .withPort(milvusPort)
            .build();
        
        log.info("Connecting to Milvus at {}:{}", milvusHost, milvusPort);
        return new MilvusServiceClient(connectParam);
    }

    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
            .collectionName(collectionName)
            .databaseName("default")
            .embeddingDimension(1536)
            .build();
    }
}

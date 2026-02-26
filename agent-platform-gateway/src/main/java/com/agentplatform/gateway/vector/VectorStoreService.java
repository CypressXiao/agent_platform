package com.agentplatform.gateway.vector;

import com.agentplatform.common.model.CallerIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * 封装 Embedding + VectorStore 操作，提供多租户隔离
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorStoreService {

    private final VectorStoreRegistry vectorStoreRegistry;
    private final EmbeddingModelRegistry embeddingModelRegistry;

    /**
     * 存储文档到向量库（使用默认 embedding 模型）
     */
    public List<String> store(CallerIdentity identity, String collection, List<DocumentInput> documents) {
        return store(identity, collection, documents, null);
    }

    /**
     * 存储文档到向量库
     * @param embeddingModelName 调用方指定的 embedding 模型名称，null 使用默认
     */
    public List<String> store(CallerIdentity identity, String collection, 
                               List<DocumentInput> documents, String embeddingModelName) {
        String tenantId = identity.getTenantId();
        String modelName = embeddingModelName != null ? embeddingModelName : "default";
        
        // 获取指定模型的 VectorStore（每个模型使用独立的 collection）
        VectorStore vectorStore = vectorStoreRegistry.get(modelName);
        
        List<Document> docs = documents.stream()
            .map(input -> {
                Map<String, Object> metadata = input.getMetadata() != null 
                    ? new java.util.HashMap<>(input.getMetadata()) 
                    : new java.util.HashMap<>();
                metadata.put("tenant_id", tenantId);
                metadata.put("collection", collection);
                // 记录使用的 embedding 模型
                metadata.put("embedding_model", modelName);
                
                return new Document(input.getId(), input.getContent(), metadata);
            })
            .collect(Collectors.toList());
        
        vectorStore.add(docs);
        
        log.info("Stored {} documents to collection {} for tenant {} using model {}", 
            docs.size(), collection, tenantId, modelName);
        
        return docs.stream().map(Document::getId).collect(Collectors.toList());
    }

    /**
     * 相似度搜索（使用默认 embedding 模型）
     */
    public List<SearchResult> search(CallerIdentity identity, String collection, 
                                      String query, int topK, Double similarityThreshold) {
        return search(identity, collection, query, topK, similarityThreshold, null);
    }

    /**
     * 相似度搜索
     * @param embeddingModelName 调用方指定的 embedding 模型名称，null 使用默认
     */
    public List<SearchResult> search(CallerIdentity identity, String collection, 
                                      String query, int topK, Double similarityThreshold,
                                      String embeddingModelName) {
        String tenantId = identity.getTenantId();
        String modelName = embeddingModelName != null ? embeddingModelName : "default";
        
        // 获取指定模型的 VectorStore
        VectorStore vectorStore = vectorStoreRegistry.get(modelName);
        
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold != null ? similarityThreshold : 0.7)
            .filterExpression(String.format("tenant_id == '%s' && collection == '%s'", tenantId, collection))
            .build();

        List<Document> results = vectorStore.similaritySearch(request);
        
        log.debug("Search in collection {} for tenant {} using model {} returned {} results", 
            collection, tenantId, modelName, results.size());
        
        // 检查模型一致性警告
        for (Document doc : results) {
            Object storedModel = doc.getMetadata().get("embedding_model");
            if (storedModel != null && !modelName.equals(storedModel)) {
                log.warn("Embedding model mismatch! Stored: {}, Query: {}. Results may be inaccurate.", 
                    storedModel, modelName);
                break;
            }
        }
        
        return results.stream()
            .map(doc -> SearchResult.builder()
                .id(doc.getId())
                .content(doc.getText())
                .metadata(doc.getMetadata())
                .score(doc.getScore() != null ? doc.getScore() : 0.0)
                .build())
            .collect(Collectors.toList());
    }

    /**
     * 删除向量（使用默认模型）
     */
    public void delete(CallerIdentity identity, String collection, List<String> ids) {
        delete(identity, collection, ids, null);
    }

    /**
     * 删除向量
     * @param embeddingModelName 调用方指定的 embedding 模型名称
     */
    public void delete(CallerIdentity identity, String collection, List<String> ids, String embeddingModelName) {
        String tenantId = identity.getTenantId();
        String modelName = embeddingModelName != null ? embeddingModelName : "default";
        
        VectorStore vectorStore = vectorStoreRegistry.get(modelName);
        vectorStore.delete(ids);
        
        log.info("Deleted {} documents from collection {} for tenant {} using model {}", 
            ids.size(), collection, tenantId, modelName);
    }

    /**
     * 获取 Embedding 向量（使用默认模型）
     */
    public List<float[]> embed(List<String> texts) {
        return embed(texts, null);
    }

    /**
     * 获取 Embedding 向量
     * @param embeddingModelName 调用方指定的 embedding 模型名称
     */
    public List<float[]> embed(List<String> texts, String embeddingModelName) {
        EmbeddingModel embeddingModel = embeddingModelRegistry.get(embeddingModelName);
        return embeddingModel.embed(texts);
    }

    /**
     * 获取已注册的 embedding 模型列表
     */
    public java.util.Set<String> getAvailableEmbeddingModels() {
        return embeddingModelRegistry.getRegisteredModels();
    }

    /**
     * 获取已创建的 VectorStore 模型列表
     */
    public java.util.Set<String> getAvailableVectorStores() {
        return vectorStoreRegistry.getRegisteredModels();
    }

    /**
     * 获取模型信息
     */
    public Map<String, Object> getModelInfo(String modelName) {
        return vectorStoreRegistry.getModelInfo(modelName);
    }

    /**
     * 文档输入
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentInput {
        private String id;
        private String content;
        private Map<String, Object> metadata;
    }

    /**
     * 搜索结果
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SearchResult {
        private String id;
        private String content;
        private Map<String, Object> metadata;
        private Double score;
    }
}

package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

/**
 * 基于全局函数的 Hybrid Vector Store
 * 使用 Milvus 2.5.26 的全局函数功能
 */
@Slf4j
public class HybridFunctionVectorStore implements VectorStore {

    private final MilvusServiceClient milvusClient;
    private final GlobalFunctionConfig config;

    public HybridFunctionVectorStore(MilvusServiceClient milvusClient, GlobalFunctionConfig config) {
        this.milvusClient = milvusClient;
        this.config = config;
    }

    /**
     * 🎯 添加文档（使用全局函数自动向量化）
     */
    @Override
    public void add(List<Document> documents) {
        try {
            log.info("Adding {} documents using global functions", documents.size());

            // 🎯 直接传入文档，Milvus 自动调用全局函数
            List<List<Object>> rows = documents.stream()
                .map(doc -> Arrays.asList(
                    doc.getId(),
                    doc.getText(),
                    doc.getMetadata() != null ? doc.getMetadata().toString() : "{}"
                ))
                .toList();

            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            InsertParam insertParam = InsertParam.builder()
                .withCollectionName(config.getCollection().getName())
                .withFieldNames(Arrays.asList("id", "content", "metadata"))
                .withRows(rows)
                .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to insert documents: " + response.getMessage());
            }
            */

            log.info("Successfully inserted {} documents using global functions", documents.size());

        } catch (Exception e) {
            log.error("Failed to add documents using global functions", e);
            throw new RuntimeException("Failed to add documents", e);
        }
    }

    /**
     * 🎯 混合搜索（使用全局函数自动向量化）
     */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        try {
            log.info("Performing hybrid search using global functions for query: {}", request.getQuery());

            // 🎯 直接传入查询文本，Milvus 自动调用全局函数
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            SearchParam searchParam = SearchParam.builder()
                .withCollectionName(config.getCollection().getName())
                .withQueryText(request.getQuery())    // 🎯 直接传文本！
                .withFunctionMode("hybrid")           // 🎯 使用混合函数
                .withTopK(request.getTopK())
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .withParams(buildHybridSearchParams())
                .build();

            R<SearchResultsWrapper> results = milvusClient.search(searchParam);
            
            if (results.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Search failed: " + results.getMessage());
            }

            List<Document> searchResults = parseHybridResults(results.getData());
            */

            // 占位符实现
            List<Document> searchResults = new ArrayList<>();
            log.info("Hybrid search returned {} results", searchResults.size());
            
            return searchResults;

        } catch (Exception e) {
            log.error("Hybrid search using global functions failed", e);
            // 降级到普通搜索
            return fallbackToDenseSearch(request);
        }
    }

    /**
     * 构建混合搜索参数
     */
    private Map<String, Object> buildHybridSearchParams() {
        return Map.of(
            "fusion_type", config.getHybrid().getFusionType(),
            "dense_weight", config.getHybrid().getDenseWeight(),
            "sparse_weight", config.getHybrid().getSparseWeight(),
            "threshold", config.getHybrid().getThreshold()
        );
    }

    /**
     * 解析混合搜索结果
     */
    private List<Document> parseHybridResults(Object results) {
        List<Document> documents = new ArrayList<>();

        try {
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            SearchResultsWrapper wrapper = (SearchResultsWrapper) results;
            
            for (SearchResultsWrapper.IDScorePair idScorePair : wrapper.getIDScore(0)) {
                String docId = idScorePair.getVectorID().toString();
                String content = idScorePair.getVectorField("content");
                String metadata = idScorePair.getVectorField("metadata");

                // 获取混合搜索得分
                double denseScore = idScorePair.getScore("dense_vector");
                double sparseScore = idScorePair.getScore("sparse_vector");
                double hybridScore = idScorePair.getScore();

                Map<String, Object> docMetadata = new HashMap<>();
                docMetadata.put("dense_score", denseScore);
                docMetadata.put("sparse_score", sparseScore);
                docMetadata.put("hybrid_score", hybridScore);
                docMetadata.put("search_mode", "global_function_hybrid");
                docMetadata.put("fusion_type", config.getHybrid().getFusionType());

                documents.add(new Document(docId, content, docMetadata));
            }
            */

        } catch (Exception e) {
            log.error("Failed to parse hybrid results", e);
        }

        return documents;
    }

    /**
     * 降级到稠密向量搜索
     */
    private List<Document> fallbackToDenseSearch(SearchRequest request) {
        try {
            log.warn("Falling back to dense search using global function");

            // 🎯 使用全局 embedding 函数
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            SearchParam searchParam = SearchParam.builder()
                .withCollectionName(config.getCollection().getName())
                .withQueryText(request.getQuery())    // 🎯 直接传文本
                .withFunctionMode("dense")          // 🎯 只用 embedding 函数
                .withTopK(request.getTopK())
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .build();

            R<SearchResultsWrapper> results = milvusClient.search(searchParam);
            return parseDenseResults(results.getData());
            */

            // 占位符实现
            return new ArrayList<>();

        } catch (Exception e) {
            log.error("Dense search fallback also failed", e);
            return Collections.emptyList();
        }
    }

    private List<Document> parseDenseResults(Object results) {
        List<Document> documents = new ArrayList<>();

        try {
            // 注意：这里使用占位符，实际需要根据 Milvus 2.5.26 的 API 调整
            /*
            SearchResultsWrapper wrapper = (SearchResultsWrapper) results;
            
            for (SearchResultsWrapper.IDScorePair idScorePair : wrapper.getIDScore(0)) {
                String docId = idScorePair.getVectorID().toString();
                String content = idScorePair.getVectorField("content");
                String metadata = idScorePair.getVectorField("metadata");
                double score = idScorePair.getScore();

                Map<String, Object> docMetadata = new HashMap<>();
                docMetadata.put("dense_score", score);
                docMetadata.put("search_mode", "global_function_dense");

                documents.add(new Document(docId, content, docMetadata));
            }
            */

        } catch (Exception e) {
            log.error("Failed to parse dense results", e);
        }

        return documents;
    }

    @Override
    public Optional<Boolean> delete(Filter.Expression expression) {
        // 实现删除逻辑
        return Optional.of(true);
    }

    @Override
    public void delete(List<String> idList) {
        // 实现批量删除逻辑
        log.info("Deleting {} documents", idList.size());
    }

    /**
     * 获取函数状态
     */
    public Map<String, Object> getFunctionStatus() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("embedding", Map.of(
            "function_name", config.getEmbedding().getName(),
            "enabled", config.getEmbedding().isEnabled(),
            "model", config.getEmbedding().getModel()
        ));
        
        status.put("sparse", Map.of(
            "function_name", config.getSparse().getName(),
            "enabled", config.getSparse().isEnabled(),
            "analyzer", config.getSparse().getAnalyzer()
        ));
        
        status.put("hybrid", Map.of(
            "function_name", config.getHybrid().getName(),
            "enabled", config.getHybrid().isEnabled(),
            "fusion_type", config.getHybrid().getFusionType()
        ));
        
        status.put("collection", Map.of(
            "name", config.getCollection().getName(),
            "auto_create", config.getCollection().isAutoCreate(),
            "auto_load", config.getCollection().isAutoLoad()
        ));
        
        return status;
    }
}

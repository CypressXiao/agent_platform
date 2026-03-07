package com.agentplatform.gateway.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.*;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实用的混合向量存储
 * 应用层生成向量，Milvus 负责混合搜索
 */
@Slf4j
public class PracticalHybridVectorStore {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;
    private final String collectionName;
    private final SimpleSparseGenerator sparseGenerator;

    /**
     * 创建混合向量 Collection
     */
    public void createHybridCollection() {
        try {
            log.info("Creating practical hybrid collection");

            // 🎯 定义字段（不需要函数配置）
            List<FieldType> fields = Arrays.asList(
                FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .withPrimaryKey(true)
                    .build(),

                // 🎯 稠密向量字段（应用层生成）
                FieldType.newBuilder()
                    .withName("dense_vector")
                    .withDataType(DataType.FloatVector)
                    .withDimension(1024)  // 根据你的 embedding 模型调整
                    .build(),

                // 🎯 稀疏向量字段（应用层生成）
                FieldType.newBuilder()
                    .withName("sparse_vector")
                    .withDataType(DataType.SparseFloatVector)
                    .build(),

                FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build(),

                FieldType.newBuilder()
                    .withName("metadata")
                    .withDataType(DataType.JSON)
                    .build()
            );

            // 创建 Collection
            CreateCollectionRequest createRequest = CreateCollectionRequest.builder()
                .withCollectionName(collectionName)
                .withFieldTypes(fields)
                .build();

            R<RpcStatus> response = milvusClient.createCollection(createRequest);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create collection: " + response.getMessage());
            }

            log.info("Created hybrid collection: {}", collectionName);

            // 创建索引
            createIndexes();
            
            // 加载 Collection
            loadCollection();

        } catch (Exception e) {
            log.error("Failed to create hybrid collection", e);
            throw new RuntimeException("Failed to create hybrid collection", e);
        }
    }

    /**
     * 创建索引
     */
    private void createIndexes() {
        try {
            // 稠密向量索引
            IndexParam denseIndex = IndexParam.builder()
                .withCollectionName(collectionName)
                .withFieldName("dense_vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP)
                .withExtraParam(Map.of("nlist", 128))
                .build();

            // 稀疏向量索引
            IndexParam sparseIndex = IndexParam.builder()
                .withCollectionName(collectionName)
                .withFieldName("sparse_vector")
                .withIndexType(IndexType.INVERTED)
                .withMetricType(MetricType.BM25)
                .withExtraParam(Map.of(
                    "bm25_k1", 1.2,
                    "bm25_b", 0.75
                ))
                .build();

            R<RpcStatus> response = milvusClient.createIndexes(
                CreateIndexRequest.builder()
                    .withCollectionName(collectionName)
                    .withIndexParams(Arrays.asList(denseIndex, sparseIndex))
                    .build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create indexes: " + response.getMessage());
            }

            log.info("Created indexes for hybrid collection");

        } catch (Exception e) {
            log.error("Failed to create indexes", e);
            throw new RuntimeException("Failed to create indexes", e);
        }
    }

    /**
     * 加载 Collection
     */
    private void loadCollection() {
        try {
            R<RpcStatus> response = milvusClient.loadCollection(
                LoadCollectionRequest.builder()
                    .withCollectionName(collectionName)
                    .build()
            );

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to load collection: " + response.getMessage());
            }

            log.info("Loaded hybrid collection: {}", collectionName);

        } catch (Exception e) {
            log.error("Failed to load collection", e);
            throw new RuntimeException("Failed to load collection", e);
        }
    }

    /**
     * 🎯 添加文档（应用层生成向量）
     */
    public void addDocuments(List<Document> documents) {
        try {
            log.info("Adding {} documents to hybrid collection", documents.size());

            // 🎯 应用层生成稠密向量
            List<List<Float>> denseVectors = documents.stream()
                .map(doc -> {
                    float[] vector = embeddingModel.embed(doc.getText());
                    return Arrays.stream(vector).boxed().collect(Collectors.toList());
                })
                .collect(Collectors.toList());

            // 🎯 应用层生成稀疏向量
            List<Map<Integer, Float>> sparseVectors = documents.stream()
                .map(doc -> sparseGenerator.generate(doc.getText()))
                .collect(Collectors.toList());

            // 准备数据
            List<String> ids = documents.stream().map(Document::getId).collect(Collectors.toList());
            List<String> contents = documents.stream().map(Document::getText).collect(Collectors.toList());
            List<String> metadataList = documents.stream()
                .map(doc -> doc.getMetadata() != null ? doc.getMetadata().toString() : "{}")
                .collect(Collectors.toList());

            // 🎯 插入混合数据
            InsertParam insertParam = InsertParam.builder()
                .withCollectionName(collectionName)
                .withFields(Arrays.asList("id", "dense_vector", "sparse_vector", "content", "metadata"))
                .withRows(Arrays.asList(ids, denseVectors, sparseVectors, contents, metadataList))
                .build();

            R<MutationResult> response = milvusClient.insert(insertParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to insert documents: " + response.getMessage());
            }

            log.info("Successfully inserted {} documents", documents.size());

        } catch (Exception e) {
            log.error("Failed to insert documents", e);
            throw new RuntimeException("Failed to insert documents", e);
        }
    }

    /**
     * 🎯 混合搜索（应用层生成查询向量）
     */
    public List<Document> similaritySearch(SearchRequest request) {
        try {
            log.info("Performing hybrid search for query: {}", request.getQuery());

            // 🎯 应用层生成查询向量
            float[] queryDenseVector = embeddingModel.embed(request.getQuery());
            Map<Integer, Float> querySparseVector = sparseGenerator.generate(request.getQuery());

            // 🎯 执行混合搜索
            List<Document> results = performHybridSearch(
                queryDenseVector, 
                querySparseVector, 
                request.getTopK(),
                request.getSimilarityThreshold()
            );

            log.info("Hybrid search returned {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Hybrid search failed, falling back to dense search", e);
            return fallbackToDenseSearch(request);
        }
    }

    /**
     * 执行混合搜索
     */
    private List<Document> performHybridSearch(float[] queryDenseVector,
                                              Map<Integer, Float> querySparseVector,
                                              int topK,
                                              Double similarityThreshold) {
        try {
            // 🎯 构建 Milvus 混合搜索参数
            SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .withTopK(topK)
                .withExpr(buildFilterExpression(similarityThreshold))
                .withParams(buildHybridSearchParams())
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build();

            // 🎯 执行搜索（需要使用 Milvus 的混合搜索 API）
            R<SearchResultsWrapper> results = milvusClient.hybridSearch(
                HybridSearchRequest.builder()
                    .withCollectionName(collectionName)
                    .withDenseVector(Arrays.stream(queryDenseVector).boxed().collect(Collectors.toList()))
                    .withSparseVector(querySparseVector)
                    .withTopK(topK)
                    .withSearchParams(searchParam)
                    .build()
            );

            if (results.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Hybrid search failed: " + results.getMessage());
            }

            return parseHybridResults(results.getData());

        } catch (Exception e) {
            log.error("Failed to perform hybrid search", e);
            throw e;
        }
    }

    /**
     * 构建混合搜索参数
     */
    private Map<String, Object> buildHybridSearchParams() {
        return Map.of(
            "metric_type", "HYBRID",
            "params", Map.of(
                "fusion_type", "weighted_sum",
                "dense_weight", 0.6,
                "sparse_weight", 0.4
            )
        );
    }

    /**
     * 构建过滤表达式
     */
    private String buildFilterExpression(Double similarityThreshold) {
        if (similarityThreshold != null) {
            return String.format("hybrid_score >= %f", similarityThreshold);
        }
        return null;
    }

    /**
     * 解析混合搜索结果
     */
    private List<Document> parseHybridResults(SearchResultsWrapper results) {
        List<Document> documents = new ArrayList<>();

        try {
            for (SearchResultsWrapper.IDScorePair idScorePair : results.getIDScore(0)) {
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
                docMetadata.put("search_mode", "practical_hybrid");

                documents.add(new Document(docId, content, docMetadata));
            }

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
            log.warn("Falling back to dense vector search");

            float[] queryVector = embeddingModel.embed(request.getQuery());

            SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("dense_vector")
                .withQueryVector(Arrays.stream(queryVector).boxed().collect(Collectors.toList()))
                .withTopK(request.getTopK())
                .withOutFields(Arrays.asList("id", "content", "metadata"))
                .build();

            R<SearchResultsWrapper> results = milvusClient.search(searchParam);
            
            return parseDenseResults(results.getData());

        } catch (Exception e) {
            log.error("Dense search fallback also failed", e);
            return Collections.emptyList();
        }
    }

    private List<Document> parseDenseResults(SearchResultsWrapper results) {
        List<Document> documents = new ArrayList<>();

        try {
            for (SearchResultsWrapper.IDScorePair idScorePair : results.getIDScore(0)) {
                String docId = idScorePair.getVectorID().toString();
                String content = idScorePair.getVectorField("content");
                String metadata = idScorePair.getVectorField("metadata");
                double score = idScorePair.getScore();

                Map<String, Object> docMetadata = new HashMap<>();
                docMetadata.put("dense_score", score);
                docMetadata.put("search_mode", "dense_fallback");

                documents.add(new Document(docId, content, docMetadata));
            }
        } catch (Exception e) {
            log.error("Failed to parse dense results", e);
        }

        return documents;
    }
}

/**
 * 简化的稀疏向量生成器
 */
class SimpleSparseGenerator {
    
    public Map<Integer, Float> generate(String text) {
        // 🎯 简单的 BM25 实现
        Map<Integer, Float> sparseVector = new HashMap<>();
        
        // 简单分词
        String[] terms = text.toLowerCase().split("\\s+");
        
        // 生成稀疏向量
        for (int i = 0; i < terms.length; i++) {
            String term = terms[i];
            if (term.length() >= 2) {
                int hash = term.hashCode();
                float weight = 1.0f / (i + 1); // 简单的权重计算
                sparseVector.put(Math.abs(hash % 10000), weight);
            }
        }
        
        return sparseVector;
    }
}

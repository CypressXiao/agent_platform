package com.agentplatform.gateway.vector;

import com.agentplatform.common.model.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 向量存储 REST API（租户级别）
 * 提供向量的存储、检索、删除等操作
 */
@RestController
@RequestMapping("/api/v1/vectors")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Vector", description = "向量存储/检索服务")
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class VectorController {

    private final VectorStoreService vectorStoreService;

    /**
     * 存储文档到向量库
     */
    @Operation(summary = "存储文档", description = "将文档转换为向量并存储到指定 Collection")
    @PostMapping("/store")
    public ResponseEntity<Map<String, Object>> store(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @RequestBody StoreRequest request) {
        
        List<String> ids = vectorStoreService.store(
            identity,
            request.getCollection(),
            request.getDocuments(),
            request.getEmbeddingModel()
        );

        String modelUsed = request.getEmbeddingModel() != null ? request.getEmbeddingModel() : "default";
        return ResponseEntity.ok(Map.of(
            "success", true,
            "collection", request.getCollection(),
            "embedding_model", modelUsed,
            "document_ids", ids,
            "count", ids.size()
        ));
    }

    /**
     * 向量相似度检索
     */
    @Operation(summary = "向量检索", description = "根据查询文本进行相似度检索")
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @RequestBody SearchRequest request) {
        
        int topK = request.getTopK() != null ? request.getTopK() : 5;
        double threshold = request.getThreshold() != null ? request.getThreshold() : 0.7;

        List<VectorStoreService.SearchResult> results = vectorStoreService.search(
            identity,
            request.getCollection(),
            request.getQuery(),
            topK,
            threshold,
            request.getEmbeddingModel()
        );

        String modelUsed = request.getEmbeddingModel() != null ? request.getEmbeddingModel() : "default";
        return ResponseEntity.ok(Map.of(
            "success", true,
            "collection", request.getCollection(),
            "embedding_model", modelUsed,
            "query", request.getQuery(),
            "results", results,
            "count", results.size()
        ));
    }

    /**
     * 删除向量
     */
    @Operation(summary = "删除向量", description = "根据文档 ID 删除向量")
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> delete(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @RequestBody DeleteRequest request) {
        
        vectorStoreService.delete(identity, request.getCollection(), request.getDocumentIds(), request.getEmbeddingModel());

        String modelUsed = request.getEmbeddingModel() != null ? request.getEmbeddingModel() : "default";
        return ResponseEntity.ok(Map.of(
            "success", true,
            "collection", request.getCollection(),
            "embedding_model", modelUsed,
            "deleted_ids", request.getDocumentIds(),
            "count", request.getDocumentIds().size()
        ));
    }

    /**
     * 获取文本的 Embedding 向量
     */
    @Operation(summary = "生成 Embedding", description = "将文本转换为向量表示")
    @PostMapping("/embed")
    public ResponseEntity<Map<String, Object>> embed(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @RequestBody EmbedRequest request) {
        
        List<float[]> embeddings = vectorStoreService.embed(request.getTexts(), request.getEmbeddingModel());

        String modelUsed = request.getEmbeddingModel() != null ? request.getEmbeddingModel() : "default";
        return ResponseEntity.ok(Map.of(
            "success", true,
            "embedding_model", modelUsed,
            "embeddings", embeddings,
            "dimension", embeddings.isEmpty() ? 0 : embeddings.get(0).length,
            "count", embeddings.size()
        ));
    }

    // ========== Request DTOs ==========

    @Data
    @Schema(description = "存储请求")
    public static class StoreRequest {
        @Schema(description = "Collection 名称", example = "my_docs")
        private String collection;
        @Schema(description = "文档列表")
        private List<VectorStoreService.DocumentInput> documents;
        @Schema(description = "Embedding 模型名称", example = "bge-large-zh", defaultValue = "default")
        private String embeddingModel;
    }

    @Data
    @Schema(description = "检索请求")
    public static class SearchRequest {
        @Schema(description = "Collection 名称", example = "my_docs")
        private String collection;
        @Schema(description = "查询文本", example = "如何使用向量数据库")
        private String query;
        @Schema(description = "返回结果数量", example = "5", defaultValue = "5")
        private Integer topK;
        @Schema(description = "相似度阈值", example = "0.7", defaultValue = "0.7")
        private Double threshold;
        @Schema(description = "Embedding 模型名称", example = "bge-large-zh", defaultValue = "default")
        private String embeddingModel;
    }

    @Data
    @Schema(description = "删除请求")
    public static class DeleteRequest {
        @Schema(description = "Collection 名称", example = "my_docs")
        private String collection;
        @Schema(description = "要删除的文档 ID 列表")
        private List<String> documentIds;
        @Schema(description = "Embedding 模型名称", example = "bge-large-zh", defaultValue = "default")
        private String embeddingModel;
    }

    @Data
    @Schema(description = "Embedding 请求")
    public static class EmbedRequest {
        @Schema(description = "要转换的文本列表")
        private List<String> texts;
        @Schema(description = "Embedding 模型名称", example = "bge-large-zh", defaultValue = "default")
        private String embeddingModel;
    }

    /**
     * 获取可用的 Embedding 模型列表
     */
    @Operation(summary = "获取可用模型", description = "获取已注册的 Embedding 模型列表")
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity) {
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "embedding_models", vectorStoreService.getAvailableEmbeddingModels(),
            "vector_stores", vectorStoreService.getAvailableVectorStores()
        ));
    }

    /**
     * 获取模型详情
     */
    @Operation(summary = "获取模型详情", description = "获取指定 Embedding 模型的详细信息")
    @GetMapping("/models/{modelName}")
    public ResponseEntity<Map<String, Object>> getModelInfo(
            @Parameter(hidden = true) @RequestAttribute CallerIdentity identity,
            @PathVariable String modelName) {
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "model", vectorStoreService.getModelInfo(modelName)
        ));
    }
}

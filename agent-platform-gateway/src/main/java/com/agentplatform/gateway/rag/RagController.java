package com.agentplatform.gateway.rag;

import com.agentplatform.common.model.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG API 控制器
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Tag(name = "RAG", description = "检索增强生成 API")
public class RagController {

    private final RagService ragService;

    @PostMapping("/query")
    @Operation(
        summary = "RAG 查询",
        description = "执行 RAG 查询，支持 NAIVE、ADVANCED、GRAPH 三种模式"
    )
    @ApiResponse(responseCode = "200", description = "查询成功")
    public ResponseEntity<RagResponse> query(
            @RequestAttribute CallerIdentity identity,
            @RequestBody RagQueryRequest request) {
        
        RagRequest ragRequest = RagRequest.builder()
            .collection(request.getCollection())
            .query(request.getQuery())
            .mode(request.getMode() != null ? request.getMode() : RagMode.ADVANCED)
            .topK(request.getTopK() != null ? request.getTopK() : 5)
            .similarityThreshold(request.getSimilarityThreshold() != null ? request.getSimilarityThreshold() : 0.7)
            .model(request.getModel() != null ? request.getModel() : "default")
            .systemPrompt(request.getSystemPrompt())
            .enableQueryRewrite(request.getEnableQueryRewrite() != null ? request.getEnableQueryRewrite() : true)
            .enableRerank(request.getEnableRerank() != null ? request.getEnableRerank() : true)
            .enableContextCompletion(request.getEnableContextCompletion() != null ? request.getEnableContextCompletion() : true)
            .metadataFilter(request.getMetadataFilter())
            .graphDepth(request.getGraphDepth() != null ? request.getGraphDepth() : 2)
            .includeGlobalSummary(request.getIncludeGlobalSummary() != null ? request.getIncludeGlobalSummary() : false)
            .build();

        RagResponse response = ragService.query(identity, ragRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/modes")
    @Operation(summary = "获取可用的 RAG 模式")
    public ResponseEntity<Map<String, Object>> getAvailableModes() {
        List<RagMode> modes = ragService.getAvailableModes();
        return ResponseEntity.ok(Map.of(
            "available_modes", modes,
            "default_mode", RagMode.ADVANCED
        ));
    }

    /**
     * RAG 查询请求 DTO
     */
    @lombok.Data
    @Schema(description = "RAG 查询请求")
    public static class RagQueryRequest {
        
        @Schema(description = "集合名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "knowledge_base")
        private String collection;
        
        @Schema(description = "用户查询", requiredMode = Schema.RequiredMode.REQUIRED, example = "员工入职流程是什么？")
        private String query;
        
        @Schema(description = "RAG 模式：NAIVE, ADVANCED, GRAPH", example = "ADVANCED")
        private RagMode mode;
        
        @Schema(description = "检索数量", example = "5")
        private Integer topK;
        
        @Schema(description = "相似度阈值", example = "0.7")
        private Double similarityThreshold;
        
        @Schema(description = "LLM 模型", example = "gpt-4")
        private String model;
        
        @Schema(description = "自定义系统提示词")
        private String systemPrompt;
        
        @Schema(description = "是否启用查询改写", example = "true")
        private Boolean enableQueryRewrite;
        
        @Schema(description = "是否启用重排序", example = "true")
        private Boolean enableRerank;
        
        @Schema(description = "是否启用上下文补全（SOP场景）", example = "true")
        private Boolean enableContextCompletion;
        
        @Schema(description = "元数据过滤条件")
        private Map<String, Object> metadataFilter;
        
        @Schema(description = "GraphRAG: 图遍历深度", example = "2")
        private Integer graphDepth;
        
        @Schema(description = "GraphRAG: 是否包含全局摘要", example = "false")
        private Boolean includeGlobalSummary;
    }
}

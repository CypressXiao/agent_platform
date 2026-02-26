package com.agentplatform.gateway.rag.chunking;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.vector.VectorStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档分块 API
 */
@RestController
@RequestMapping("/api/v1/chunking")
@RequiredArgsConstructor
@Tag(name = "Chunking", description = "文档分块 API")
public class ChunkingController {

    private final DocumentChunker documentChunker;
    private final VectorStoreService vectorStoreService;

    @PostMapping("/chunk")
    @Operation(summary = "分块文档", description = "将文档内容分块，返回分块结果（不存储）")
    public ResponseEntity<ChunkingResponse> chunk(@RequestBody ChunkingRequest request) {
        
        ChunkingConfig config = buildConfig(request);
        List<Chunk> chunks = documentChunker.chunk(
            request.getContent(), 
            request.getDocumentName(), 
            config
        );

        return ResponseEntity.ok(ChunkingResponse.builder()
            .documentName(request.getDocumentName())
            .totalChunks(chunks.size())
            .strategy(config.getStrategy().name())
            .chunks(chunks)
            .build());
    }

    @PostMapping("/chunk-and-store")
    @Operation(summary = "分块并存储", description = "将文档分块后直接存入向量库")
    public ResponseEntity<ChunkAndStoreResponse> chunkAndStore(
            @RequestAttribute CallerIdentity identity,
            @RequestBody ChunkAndStoreRequest request) {
        
        ChunkingConfig config = buildConfig(request);
        List<Chunk> chunks = documentChunker.chunk(
            request.getContent(), 
            request.getDocumentName(), 
            config
        );

        // 转换为 VectorStoreService 的输入格式
        List<VectorStoreService.DocumentInput> documents = chunks.stream()
            .map(chunk -> VectorStoreService.DocumentInput.builder()
                .id(chunk.getId())
                .content(chunk.getContent())
                .metadata(chunk.getMetadata())
                .build())
            .collect(Collectors.toList());

        // 存储到向量库
        List<String> storedIds = vectorStoreService.store(
            identity, 
            request.getCollection(), 
            documents
        );

        return ResponseEntity.ok(ChunkAndStoreResponse.builder()
            .documentName(request.getDocumentName())
            .collection(request.getCollection())
            .totalChunks(chunks.size())
            .storedIds(storedIds)
            .strategy(config.getStrategy().name())
            .build());
    }

    @GetMapping("/strategies")
    @Operation(summary = "获取可用的分块策略")
    public ResponseEntity<Map<String, Object>> getStrategies() {
        return ResponseEntity.ok(Map.of(
            "strategies", List.of(
                Map.of(
                    "name", "FIXED_SIZE",
                    "description", "固定大小切分，按字符数切分，支持重叠",
                    "use_case", "通用场景，简单快速"
                ),
                Map.of(
                    "name", "RECURSIVE",
                    "description", "递归切分，按分隔符递归切分，尽量保持段落完整",
                    "use_case", "通用文档"
                ),
                Map.of(
                    "name", "MARKDOWN",
                    "description", "Markdown 结构切分，按标题层级切分",
                    "use_case", "SOP 文档、技术文档（推荐）"
                ),
                Map.of(
                    "name", "DOCUMENT_BASED",
                    "description", "基于文档结构切分，自动识别文档类型",
                    "use_case", "混合类型文档"
                ),
                Map.of(
                    "name", "SEMANTIC",
                    "description", "语义切分，按语义相似度切分",
                    "use_case", "高质量要求场景"
                )
            ),
            "presets", List.of(
                Map.of("name", "SOP", "description", "SOP 文档预设"),
                Map.of("name", "GENERAL", "description", "通用文档预设"),
                Map.of("name", "LONG_DOCUMENT", "description", "长文档预设")
            )
        ));
    }

    private ChunkingConfig buildConfig(ChunkingRequest request) {
        // 使用预设配置
        if (request.getPreset() != null) {
            return switch (request.getPreset().toUpperCase()) {
                case "SOP" -> ChunkingConfig.forSOP();
                case "LONG_DOCUMENT" -> ChunkingConfig.forLongDocument();
                default -> ChunkingConfig.forGeneral();
            };
        }

        // 自定义配置
        ChunkingConfig.ChunkingConfigBuilder builder = ChunkingConfig.builder();
        
        if (request.getStrategy() != null) {
            try {
                builder.strategy(ChunkingStrategy.valueOf(request.getStrategy().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // 使用默认策略
            }
        }
        if (request.getChunkSize() != null) {
            builder.chunkSize(request.getChunkSize());
        }
        if (request.getMinChunkSize() != null) {
            builder.minChunkSize(request.getMinChunkSize());
        }
        if (request.getMaxChunkSize() != null) {
            builder.maxChunkSize(request.getMaxChunkSize());
        }
        if (request.getOverlap() != null) {
            builder.overlap(request.getOverlap());
        }
        if (request.getExtractKeywords() != null) {
            builder.extractKeywords(request.getExtractKeywords());
        }

        return builder.build();
    }

    /**
     * 分块请求
     */
    @lombok.Data
    @Schema(description = "分块请求")
    public static class ChunkingRequest {
        @Schema(description = "文档内容", requiredMode = Schema.RequiredMode.REQUIRED)
        private String content;
        
        @Schema(description = "文档名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "员工入职流程")
        private String documentName;
        
        @Schema(description = "预设配置：SOP, GENERAL, LONG_DOCUMENT", example = "SOP")
        private String preset;
        
        @Schema(description = "分块策略：FIXED_SIZE, RECURSIVE, MARKDOWN, DOCUMENT_BASED, SEMANTIC", example = "MARKDOWN")
        private String strategy;
        
        @Schema(description = "目标 chunk 大小", example = "500")
        private Integer chunkSize;
        
        @Schema(description = "最小 chunk 大小", example = "100")
        private Integer minChunkSize;
        
        @Schema(description = "最大 chunk 大小", example = "1500")
        private Integer maxChunkSize;
        
        @Schema(description = "重叠大小", example = "100")
        private Integer overlap;
        
        @Schema(description = "是否提取关键词", example = "true")
        private Boolean extractKeywords;
    }

    /**
     * 分块并存储请求
     */
    @lombok.Data
    @lombok.EqualsAndHashCode(callSuper = true)
    @Schema(description = "分块并存储请求")
    public static class ChunkAndStoreRequest extends ChunkingRequest {
        @Schema(description = "目标集合名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "sop_knowledge_base")
        private String collection;
    }

    /**
     * 分块响应
     */
    @lombok.Data
    @lombok.Builder
    @Schema(description = "分块响应")
    public static class ChunkingResponse {
        private String documentName;
        private int totalChunks;
        private String strategy;
        private List<Chunk> chunks;
    }

    /**
     * 分块并存储响应
     */
    @lombok.Data
    @lombok.Builder
    @Schema(description = "分块并存储响应")
    public static class ChunkAndStoreResponse {
        private String documentName;
        private String collection;
        private int totalChunks;
        private List<String> storedIds;
        private String strategy;
    }
}

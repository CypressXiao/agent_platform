package com.agentplatform.gateway.rag.chunking;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfile;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfileRegistry;
import com.agentplatform.gateway.vector.VectorStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档分块 API
 */
@RestController
@RequestMapping("/api/v1/chunking")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Chunking", description = "文档分块 API")
public class ChunkingController {

    private final DocumentChunker documentChunker;
    private final VectorStoreService vectorStoreService;
    private final ChunkProfileRegistry profileRegistry;
    private final GraphExtractionService graphExtractionService;
    private final GraphExtractionTask graphExtractionTask;

    @PostMapping("/chunk")
    @Operation(summary = "分块文档", description = "将文档内容分块，返回分块结果（不存储）")
    public ResponseEntity<ChunkingResponse> chunk(@RequestBody ChunkingRequest request) {
        
        ChunkingConfig config = buildConfig(request);
        ChunkProfile profile = getProfile(request.getProfile());
        
        List<Chunk> chunks = documentChunker.chunk(
            request.getContent(), 
            request.getDocumentName(), 
            config
        );

        // 使用 profile 增强 metadata
        Map<String, Object> context = buildContext(request);
        chunks = enrichChunksWithProfile(chunks, profile, context);

        return ResponseEntity.ok(ChunkingResponse.builder()
            .documentName(request.getDocumentName())
            .totalChunks(chunks.size())
            .strategy(config.getStrategy().name())
            .profile(profile.getName())
            .chunks(chunks)
            .build());
    }

    @PostMapping("/chunk-and-store")
    @Operation(summary = "分块并存储", description = "将文档分块后直接存入向量库")
    public ResponseEntity<ChunkAndStoreResponse> chunkAndStore(
            @RequestAttribute CallerIdentity identity,
            @RequestBody ChunkAndStoreRequest request) {
        
        ChunkingConfig config = buildConfig(request);
        ChunkProfile profile = getProfile(request.getProfile());
        
        List<Chunk> chunks = documentChunker.chunk(
            request.getContent(), 
            request.getDocumentName(), 
            config
        );

        // 使用 profile 增强 metadata
        Map<String, Object> context = buildContext(request);
        chunks = enrichChunksWithProfile(chunks, profile, context);

        // 校验 metadata
        for (Chunk chunk : chunks) {
            ChunkProfile.ValidationResult result = profile.validateMetadata(chunk.getMetadata());
            if (!result.valid()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Chunk metadata validation failed for profile '" + profile.getName() + "': "
                        + String.join("; ", result.errors())
                );
            }
        }

        // 转换为 VectorStoreService 的输入格式
        List<VectorStoreService.DocumentInput> documents = chunks.stream()
            .map(chunk -> VectorStoreService.DocumentInput.builder()
                .id(chunk.getId())
                .content(chunk.getContent())
                .metadata(chunk.getMetadata())
                .build())
            .collect(Collectors.toList());

        // 确定 collection 名称（优先使用请求中的，否则使用 profile 的 pattern）
        String collection = request.getCollection();
        if (collection == null || collection.isBlank()) {
            collection = profile.getCollectionPattern().replace("{model}", "default");
        }

        // 🆕 图关系提取（如果启用）- 异步处理
        if (request.getEnableGraphExtraction() != null && request.getEnableGraphExtraction()) {
            GraphExtractionMode mode = GraphExtractionMode.fromString(request.getGraphExtractionMode());
            if (mode != GraphExtractionMode.DISABLED) {
                // 异步执行图关系提取，不阻塞主流程
                graphExtractionTask.extractRelationsAsync(identity, collection, chunks, profile, mode);
                log.info("Scheduled async graph extraction for collection: {}, mode: {}", collection, mode);
            }
        }

        // 存储到向量库
        List<String> storedIds = vectorStoreService.store(
            identity, 
            collection, 
            documents,
            config.getEmbeddingModel(),
            config
        );

        return ResponseEntity.ok(ChunkAndStoreResponse.builder()
            .documentName(request.getDocumentName())
            .collection(collection)
            .totalChunks(chunks.size())
            .storedIds(storedIds)
            .strategy(config.getStrategy().name())
            .profile(profile.getName())
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
                ),
                Map.of(
                    "name", "AGENTIC",
                    "description", "LLM 辅助切分，使用大模型分析文档结构智能切分",
                    "use_case", "复杂文档、需要高质量语义切分的场景"
                )
            ),
            "presets", List.of(
                Map.of("name", "SOP", "description", "SOP 文档预设"),
                Map.of("name", "GENERAL", "description", "通用文档预设"),
                Map.of("name", "LONG_DOCUMENT", "description", "长文档预设")
            )
        ));
    }

    @GetMapping("/profiles")
    @Operation(summary = "获取可用的 Chunk Profile")
    public ResponseEntity<Map<String, Object>> getProfiles() {
        List<Map<String, Object>> profileList = profileRegistry.getRegisteredProfiles().stream()
            .map(profileRegistry::getProfileInfo)
            .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("profiles", profileList));
    }

    @GetMapping("/profiles/{name}")
    @Operation(summary = "获取指定 Profile 详情")
    public ResponseEntity<Map<String, Object>> getProfileInfo(@PathVariable String name) {
        if (!profileRegistry.exists(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profileRegistry.getProfileInfo(name));
    }

    private ChunkProfile getProfile(String profileName) {
        return profileRegistry.get(profileName);
    }

    private Map<String, Object> buildContext(ChunkingRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put("documentName", request.getDocumentName());
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        return context;
    }

    private List<Chunk> enrichChunksWithProfile(List<Chunk> chunks, ChunkProfile profile, Map<String, Object> context) {
        return chunks.stream()
            .map(chunk -> {
                Map<String, Object> enrichedMetadata = profile.enrichMetadata(chunk.getMetadata(), context);
                return Chunk.builder()
                    .id(chunk.getId())
                    .content(chunk.getContent())
                    .type(chunk.getType())
                    .startOffset(chunk.getStartOffset())
                    .endOffset(chunk.getEndOffset())
                    .index(chunk.getIndex())
                    .metadata(enrichedMetadata)
                    .keywords(chunk.getKeywords())
                    .build();
            })
            .collect(Collectors.toList());
    }

    private ChunkingConfig buildConfig(ChunkingRequest request) {
        // 根据 documentType 选择处理策略
        ChunkingConfig.DocumentType documentType = request.getDocumentType() != null 
            ? ChunkingConfig.DocumentType.valueOf(request.getDocumentType().toUpperCase())
            : ChunkingConfig.DocumentType.STANDARD;
        
        // 非标准文档直接使用 legacy profile
        if (documentType == ChunkingConfig.DocumentType.LEGACY) {
            ChunkProfile profile = getProfile("legacy");
            ChunkingConfig config = profile.getChunkingConfig();
            // 设置 profileName 和 documentType
            return ChunkingConfig.builder()
                .strategy(config.getStrategy())
                .chunkSize(config.getChunkSize())
                .minChunkSize(config.getMinChunkSize())
                .maxChunkSize(config.getMaxChunkSize())
                .overlap(config.getOverlap())
                .keywordStrategy(config.getKeywordStrategy())
                .maxKeywords(config.getMaxKeywords())
                .enableSparseVector(config.isEnableSparseVector())
                .sparseAnalyzer(config.getSparseAnalyzer())
                .semanticThreshold(config.getSemanticThreshold())
                .agenticModel(config.getAgenticModel())
                .embeddingModel(config.getEmbeddingModel())
                .embeddingMaxTokens(config.getEmbeddingMaxTokens())
                .charsPerToken(config.getCharsPerToken())
                .profileName(profile.getName())
                .documentType(documentType)
                .build();
        }
        
        // 标准文档：优先使用 profile 的配置
        if (request.getProfile() != null) {
            ChunkProfile profile = getProfile(request.getProfile());
            ChunkingConfig config = profile.getChunkingConfig();
            // 设置 profileName
            return ChunkingConfig.builder()
                .strategy(config.getStrategy())
                .chunkSize(config.getChunkSize())
                .minChunkSize(config.getMinChunkSize())
                .maxChunkSize(config.getMaxChunkSize())
                .overlap(config.getOverlap())
                .keywordStrategy(config.getKeywordStrategy())
                .maxKeywords(config.getMaxKeywords())
                .enableSparseVector(config.isEnableSparseVector())
                .sparseAnalyzer(config.getSparseAnalyzer())
                .semanticThreshold(config.getSemanticThreshold())
                .agenticModel(config.getAgenticModel())
                .embeddingModel(config.getEmbeddingModel())
                .embeddingMaxTokens(config.getEmbeddingMaxTokens())
                .charsPerToken(config.getCharsPerToken())
                .profileName(profile.getName()) // 设置 profile 名称
                .documentType(documentType)
                .build();
        }

        // 使用预设配置（映射到对应 profile）
        if (request.getPreset() != null) {
            String profileName = switch (request.getPreset().toUpperCase()) {
                case "SOP" -> "sop";
                case "KNOWLEDGE" -> "knowledge";
                default -> "knowledge";
            };
            ChunkProfile profile = getProfile(profileName);
            ChunkingConfig config = profile.getChunkingConfig();
            // 设置 profileName
            return ChunkingConfig.builder()
                .strategy(config.getStrategy())
                .chunkSize(config.getChunkSize())
                .minChunkSize(config.getMinChunkSize())
                .maxChunkSize(config.getMaxChunkSize())
                .overlap(config.getOverlap())
                .keywordStrategy(config.getKeywordStrategy())
                .maxKeywords(config.getMaxKeywords())
                .enableSparseVector(config.isEnableSparseVector())
                .sparseAnalyzer(config.getSparseAnalyzer())
                .semanticThreshold(config.getSemanticThreshold())
                .agenticModel(config.getAgenticModel())
                .embeddingModel(config.getEmbeddingModel())
                .embeddingMaxTokens(config.getEmbeddingMaxTokens())
                .charsPerToken(config.getCharsPerToken())
                .profileName(profile.getName()) // 设置 profile 名称
                .documentType(documentType)
                .build();
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
            boolean enableKeywordExtraction = request.getExtractKeywords();
            builder.keywordStrategy(enableKeywordExtraction
                ? ChunkingConfig.KeywordExtractionStrategy.MILVUS_ANALYZER
                : ChunkingConfig.KeywordExtractionStrategy.DISABLED);
            builder.enableSparseVector(enableKeywordExtraction);
        }
        if (request.getAgenticModel() != null) {
            builder.agenticModel(request.getAgenticModel());
        }

        builder.documentType(documentType);
        
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
        
        @Schema(description = "预设配置：SOP, KNOWLEDGE", example = "SOP")
        private String preset;
        
        @Schema(description = "分块策略：FIXED_SIZE, RECURSIVE, MARKDOWN, DOCUMENT_BASED, SEMANTIC, AGENTIC", example = "MARKDOWN")
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
        
        @Schema(description = "AGENTIC 模式使用的模型", example = "gpt-4")
        private String agenticModel;
        
        @Schema(description = "Chunk Profile 名称：sop, knowledge, product_doc, faq, regulation", example = "knowledge")
        private String profile;
        
        @Schema(description = "上下文信息（用于 metadata 增强）", example = "{\"productId\": \"P001\", \"version\": \"1.0\"}")
        private Map<String, Object> context;
        
        @Schema(description = "文档类型：STANDARD, LEGACY", example = "STANDARD")
        private String documentType;
        
        @Schema(description = "是否启用图关系提取", example = "false")
        private Boolean enableGraphExtraction = false;
        
        @Schema(description = "图关系提取模式：disabled, ontology_based, llm_driven", example = "ontology_based")
        private String graphExtractionMode = "disabled";
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
        private String profile;
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
        private String profile;
    }
}

package com.agentplatform.gateway.rag.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import com.agentplatform.gateway.rag.*;
import com.agentplatform.gateway.rag.component.QueryRewriter;
import com.agentplatform.gateway.rag.component.SemanticRewriteService;
import com.agentplatform.gateway.rag.component.Reranker;
import com.agentplatform.gateway.rag.component.ContextCompleter;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Advanced RAG 策略
 * 完整流程：查询改写 → 混合检索 → 重排序 → 上下文补全 → LLM 生成
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AdvancedRagStrategy implements RagStrategy {

    private final VectorStoreService vectorStoreService;
    private final LlmRouterService llmRouterService;
    private final QueryRewriter queryRewriter;
    private final SemanticRewriteService semanticRewriteService;
    private final Reranker reranker;
    private final ContextCompleter contextCompleter;

    @Override
    public RagMode supportedMode() {
        return RagMode.ADVANCED;
    }

    @Override
    public RagResponse execute(CallerIdentity identity, RagRequest request) {
        log.info("Executing Advanced RAG for collection={}, query={}", 
            request.getCollection(), request.getQuery());

        String originalQuery = request.getQuery();
        String processedQuery = originalQuery;

        // 1. 语义改写（优先使用新的 SemanticRewriteService）
        if (request.isEnableQueryRewrite()) {
            try {
                // 构建语义改写请求
                SemanticRewriteService.SemanticRewriteRequest rewriteRequest = 
                    SemanticRewriteService.SemanticRewriteRequest.builder()
                        .currentQuery(originalQuery)
                        .collection(request.getCollection())
                        .model(request.getModel())
                        // 历史对话可以从其他地方获取，暂时为空
                        .history(List.of())
                        .build();
                
                SemanticRewriteService.SemanticRewriteResult rewriteResult = 
                    semanticRewriteService.rewrite(identity, rewriteRequest);
                
                processedQuery = rewriteResult.getFinalQuery();
                
                log.info("Semantic rewrite: mode={}, reason='{}', query: {} -> {}", 
                    rewriteResult.getMode(), 
                    rewriteResult.getRewriteReason(),
                    originalQuery, 
                    processedQuery);
                
                // 如果是复合问题拆解，可以考虑多查询检索（后续扩展）
                if (rewriteResult.getMode() == SemanticRewriteService.RewriteMode.DECOMPOSITION 
                    && rewriteResult.getSubQueries() != null 
                    && !rewriteResult.getSubQueries().isEmpty()) {
                    log.debug("Detected complex query with {} sub-queries", 
                        rewriteResult.getSubQueries().size());
                    // TODO: 实现多查询检索逻辑
                }
                
            } catch (Exception e) {
                log.warn("Semantic rewrite failed, falling back to traditional query rewrite: {}", e.getMessage());
                // 降级到传统查询改写
                processedQuery = queryRewriter.rewrite(identity, originalQuery, request.getModel());
                log.debug("Fallback query rewrite: {} -> {}", originalQuery, processedQuery);
            }
        }

        // 2. 向量检索（扩大检索范围，后续重排序筛选）
        long searchStartTime = System.currentTimeMillis();
        int retrieveK = request.isEnableRerank() ? request.getTopK() * 3 : request.getTopK();
        List<VectorStoreService.SearchResult> searchResults = vectorStoreService.search(
            identity, 
            request.getCollection(), 
            processedQuery, 
            retrieveK, 
            request.getSimilarityThreshold() * 0.8 // 降低阈值，让重排序来筛选
        );
        long searchDuration = System.currentTimeMillis() - searchStartTime;

        if (searchResults.isEmpty()) {
            return RagResponse.builder()
                .success(true)
                .answer("未找到与您问题相关的文档。")
                .sources(List.of())
                .mode(RagMode.ADVANCED)
                .rewrittenQuery(processedQuery.equals(originalQuery) ? null : processedQuery)
                .build();
        }

        // 3. 重排序（可选）
        long rerankStartTime = System.currentTimeMillis();
        List<RankedResult> rankedResults;
        if (request.isEnableRerank()) {
            rankedResults = reranker.rerank(originalQuery, searchResults, request.getTopK());
            log.debug("Reranked {} results to top {}", searchResults.size(), rankedResults.size());
        } else {
            rankedResults = searchResults.stream()
                .limit(request.getTopK())
                .map(r -> new RankedResult(r, r.getScore(), null))
                .collect(Collectors.toList());
        }
        long rerankDuration = System.currentTimeMillis() - rerankStartTime;

        // 4. 上下文补全（SOP 场景，补充前后步骤）
        long contextStartTime = System.currentTimeMillis();
        List<VectorStoreService.SearchResult> contextResults;
        if (request.isEnableContextCompletion()) {
            contextResults = contextCompleter.complete(
                identity, 
                request.getCollection(), 
                rankedResults.stream().map(RankedResult::getResult).collect(Collectors.toList())
            );
        } else {
            contextResults = rankedResults.stream()
                .map(RankedResult::getResult)
                .collect(Collectors.toList());
        }
        long contextDuration = System.currentTimeMillis() - contextStartTime;

        // 5. 构建响应（搜索结果已完成）
        List<RagResponse.Source> sources = rankedResults.stream()
            .map(r -> RagResponse.Source.builder()
                .id(r.getResult().getId())
                .score(r.getResult().getScore())
                .rerankScore(r.getRerankScore())
                .contentPreview(truncate(r.getResult().getContent(), 200))
                .metadata(r.getResult().getMetadata())
                .build())
            .collect(Collectors.toList());
        
        // 构建检索统计信息
        RagResponse.SearchStats searchStats = RagResponse.SearchStats.builder()
            .initialRetrieved(searchResults.size())
            .rerankedCount(rankedResults.size())
            .contextCompletedCount(contextResults.size())
            .hybridSearch(false) // 暂时固定为 false，后续根据实际配置设置
            .searchDurationMs(searchDuration)
            .rerankDurationMs(request.isEnableRerank() ? rerankDuration : null)
            .contextCompletionDurationMs(request.isEnableContextCompletion() ? contextDuration : null)
            .searchType("dense") // 暂时固定为 dense，后续根据实际配置设置
            .similarityThreshold(request.getSimilarityThreshold())
            .build();

        // 6. 检查是否需要生成 LLM 答案
        if (!request.isEnableAnswerLLM()) {
            // 仅返回检索结果，不调用 LLM
            log.info("RAG search-only mode: returning {} sources without LLM generation", sources.size());
            return RagResponse.builder()
                .success(true)
                .answer("仅检索模式，请查看下方相关文档。如需生成答案，请启用 enableAnswerLLM 参数。")
                .sources(sources)
                .mode(RagMode.ADVANCED)
                .rewrittenQuery(processedQuery.equals(originalQuery) ? null : processedQuery)
                .searchOnlyMode(true)
                .searchStats(searchStats)
                .build();
        }

        // 7. 生成 LLM 答案（传统模式）
        String context = buildContext(contextResults);
        String systemPrompt = request.getSystemPrompt() != null ? request.getSystemPrompt() :
            buildSystemPrompt();

        String userPrompt = String.format(
            "上下文：\n%s\n\n---\n\n问题：%s\n\n请根据上下文回答：",
            context, originalQuery);

        Map<String, Object> llmResult = llmRouterService.chat(
            identity,
            request.getModel(),
            List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            0.7,
            null
        );

        // 8. 构建完整响应
        return RagResponse.builder()
            .success(true)
            .answer((String) llmResult.get("content"))
            .sources(sources)
            .model((String) llmResult.getOrDefault("model", request.getModel()))
            .usage(castToMap(llmResult.getOrDefault("usage", Map.of())))
            .mode(RagMode.ADVANCED)
            .rewrittenQuery(processedQuery.equals(originalQuery) ? null : processedQuery)
            .searchOnlyMode(false)
            .searchStats(searchStats)
            .build();
    }

    private String buildSystemPrompt() {
        return """
            你是一个专业的知识库助手，专门回答基于 SOP（标准操作流程）的问题。
            
            回答要求：
            1. 严格基于提供的上下文回答，不要编造信息
            2. 如果是步骤类问题，按步骤编号清晰列出
            3. 如果上下文不足以回答，明确说明
            4. 引用来源文档，格式：【文档ID】
            5. 如果涉及条件分支，说明不同情况的处理方式
            """;
    }

    private String buildContext(List<VectorStoreService.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            VectorStoreService.SearchResult r = results.get(i);
            Map<String, Object> meta = r.getMetadata();
            
            sb.append(String.format("【文档 %s", r.getId()));
            if (meta != null) {
                if (meta.containsKey("sop_name")) {
                    sb.append("，SOP: ").append(meta.get("sop_name"));
                }
                if (meta.containsKey("step_number")) {
                    sb.append("，步骤 ").append(meta.get("step_number"));
                }
                if (meta.containsKey("chunk_type")) {
                    sb.append("，类型: ").append(meta.get("chunk_type"));
                }
            }
            sb.append("】\n");
            sb.append(r.getContent());
            
            if (i < results.size() - 1) {
                sb.append("\n\n---\n\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return Map.of();
    }

    /**
     * 重排序结果包装
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class RankedResult {
        private VectorStoreService.SearchResult result;
        private Double originalScore;
        private Double rerankScore;
    }
}

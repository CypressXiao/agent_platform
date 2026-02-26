package com.agentplatform.gateway.rag.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import com.agentplatform.gateway.rag.*;
import com.agentplatform.gateway.rag.component.QueryRewriter;
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

        // 1. 查询改写（可选）
        if (request.isEnableQueryRewrite()) {
            processedQuery = queryRewriter.rewrite(identity, originalQuery, request.getModel());
            log.debug("Query rewritten: {} -> {}", originalQuery, processedQuery);
        }

        // 2. 向量检索（扩大检索范围，后续重排序筛选）
        int retrieveK = request.isEnableRerank() ? request.getTopK() * 3 : request.getTopK();
        List<VectorStoreService.SearchResult> searchResults = vectorStoreService.search(
            identity, 
            request.getCollection(), 
            processedQuery, 
            retrieveK, 
            request.getSimilarityThreshold() * 0.8 // 降低阈值，让重排序来筛选
        );

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

        // 4. 上下文补全（SOP 场景，补充前后步骤）
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

        // 5. 构建上下文
        String context = buildContext(contextResults);

        // 6. 构建 Prompt 并调用 LLM
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

        // 7. 构建响应
        List<RagResponse.Source> sources = rankedResults.stream()
            .map(r -> RagResponse.Source.builder()
                .id(r.getResult().getId())
                .score(r.getResult().getScore())
                .rerankScore(r.getRerankScore())
                .contentPreview(truncate(r.getResult().getContent(), 200))
                .metadata(r.getResult().getMetadata())
                .build())
            .collect(Collectors.toList());

        return RagResponse.builder()
            .success(true)
            .answer((String) llmResult.get("content"))
            .sources(sources)
            .model((String) llmResult.getOrDefault("model", request.getModel()))
            .usage(castToMap(llmResult.getOrDefault("usage", Map.of())))
            .mode(RagMode.ADVANCED)
            .rewrittenQuery(processedQuery.equals(originalQuery) ? null : processedQuery)
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

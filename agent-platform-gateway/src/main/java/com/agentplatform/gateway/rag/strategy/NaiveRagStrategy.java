package com.agentplatform.gateway.rag.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import com.agentplatform.gateway.rag.*;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Naive RAG 策略
 * 基础流程：向量检索 → 拼接上下文 → LLM 生成
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NaiveRagStrategy implements RagStrategy {

    private final VectorStoreService vectorStoreService;
    private final LlmRouterService llmRouterService;

    @Override
    public RagMode supportedMode() {
        return RagMode.NAIVE;
    }

    @Override
    public RagResponse execute(CallerIdentity identity, RagRequest request) {
        log.info("Executing Naive RAG for collection={}, query={}", 
            request.getCollection(), request.getQuery());

        // 1. 向量检索
        List<VectorStoreService.SearchResult> searchResults = vectorStoreService.search(
            identity, 
            request.getCollection(), 
            request.getQuery(), 
            request.getTopK(), 
            request.getSimilarityThreshold()
        );

        if (searchResults.isEmpty()) {
            return RagResponse.builder()
                .success(true)
                .answer("未找到与您问题相关的文档。")
                .sources(List.of())
                .mode(RagMode.NAIVE)
                .build();
        }

        // 2. 构建上下文
        String context = buildContext(searchResults);

        // 3. 构建 Prompt 并调用 LLM
        String systemPrompt = request.getSystemPrompt() != null ? request.getSystemPrompt() :
            "你是一个专业的知识库助手。请根据提供的上下文回答用户问题。" +
            "如果上下文中没有相关信息，请明确说明。请引用你使用的文档来源。";

        String userPrompt = String.format(
            "上下文：\n%s\n\n---\n\n问题：%s\n\n请根据上下文回答：",
            context, request.getQuery());

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

        // 4. 构建响应
        List<RagResponse.Source> sources = searchResults.stream()
            .map(r -> RagResponse.Source.builder()
                .id(r.getId())
                .score(r.getScore())
                .contentPreview(truncate(r.getContent(), 200))
                .metadata(r.getMetadata())
                .build())
            .collect(Collectors.toList());

        return RagResponse.builder()
            .success(true)
            .answer((String) llmResult.get("content"))
            .sources(sources)
            .model((String) llmResult.getOrDefault("model", request.getModel()))
            .usage(castToMap(llmResult.getOrDefault("usage", Map.of())))
            .mode(RagMode.NAIVE)
            .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return Map.of();
    }

    private String buildContext(List<VectorStoreService.SearchResult> results) {
        return results.stream()
            .map(r -> String.format("【文档 %s，相似度: %.2f】\n%s", 
                r.getId(), r.getScore(), r.getContent()))
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}

package com.agentplatform.gateway.rag.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import com.agentplatform.gateway.rag.*;
import com.agentplatform.gateway.rag.component.GraphStore;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Graph RAG 策略
 * 结合知识图谱的 RAG：向量检索 + 图遍历 + LLM 生成
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.rag.graph.enabled", havingValue = "true", matchIfMissing = false)
public class GraphRagStrategy implements RagStrategy {

    private final VectorStoreService vectorStoreService;
    private final LlmRouterService llmRouterService;
    private final GraphStore graphStore;

    @Override
    public RagMode supportedMode() {
        return RagMode.GRAPH;
    }

    @Override
    public RagResponse execute(CallerIdentity identity, RagRequest request) {
        log.info("Executing Graph RAG for collection={}, query={}", 
            request.getCollection(), request.getQuery());

        // 1. 向量检索获取初始实体
        List<VectorStoreService.SearchResult> vectorResults = vectorStoreService.search(
            identity, 
            request.getCollection(), 
            request.getQuery(), 
            request.getTopK(), 
            request.getSimilarityThreshold()
        );

        if (vectorResults.isEmpty()) {
            return RagResponse.builder()
                .success(true)
                .answer("未找到与您问题相关的文档。")
                .sources(List.of())
                .mode(RagMode.GRAPH)
                .build();
        }

        // 2. 从检索结果中提取实体
        Set<String> entities = extractEntities(vectorResults);
        log.debug("Extracted {} entities from vector results", entities.size());

        // 3. 图遍历获取关联实体和关系
        List<RagResponse.GraphRelation> graphRelations = new ArrayList<>();
        Set<String> expandedEntities = new HashSet<>(entities);
        
        for (String entity : entities) {
            List<GraphStore.Relation> relations = graphStore.getRelations(
                identity.getTenantId(), 
                entity, 
                request.getGraphDepth()
            );
            
            for (GraphStore.Relation rel : relations) {
                graphRelations.add(RagResponse.GraphRelation.builder()
                    .sourceEntity(rel.getSource())
                    .relation(rel.getRelationType())
                    .targetEntity(rel.getTarget())
                    .properties(rel.getProperties())
                    .build());
                
                expandedEntities.add(rel.getTarget());
            }
        }
        log.debug("Graph traversal found {} relations, expanded to {} entities", 
            graphRelations.size(), expandedEntities.size());

        // 4. 获取全局摘要（可选）
        String globalSummary = "";
        if (request.isIncludeGlobalSummary()) {
            globalSummary = graphStore.getGlobalSummary(
                identity.getTenantId(), 
                request.getCollection()
            );
        }

        // 5. 构建增强上下文
        String context = buildGraphContext(vectorResults, graphRelations, globalSummary);

        // 6. 构建 Prompt 并调用 LLM
        String systemPrompt = request.getSystemPrompt() != null ? request.getSystemPrompt() :
            buildGraphSystemPrompt();

        String userPrompt = String.format(
            "上下文：\n%s\n\n---\n\n问题：%s\n\n请根据上下文和知识图谱关系回答：",
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

        // 7. 构建响应
        List<RagResponse.Source> sources = vectorResults.stream()
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
            .graphRelations(graphRelations)
            .model((String) llmResult.getOrDefault("model", request.getModel()))
            .usage(castToMap(llmResult.getOrDefault("usage", Map.of())))
            .mode(RagMode.GRAPH)
            .build();
    }

    /**
     * 从检索结果中提取实体
     */
    private Set<String> extractEntities(List<VectorStoreService.SearchResult> results) {
        Set<String> entities = new HashSet<>();
        
        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata == null) continue;

            // 从 metadata 中提取实体
            if (metadata.containsKey("entities")) {
                Object entitiesObj = metadata.get("entities");
                if (entitiesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> entityList = (List<String>) entitiesObj;
                    entities.addAll(entityList);
                }
            }

            // SOP 名称作为实体
            if (metadata.containsKey("sop_name")) {
                entities.add((String) metadata.get("sop_name"));
            }
        }
        
        return entities;
    }

    /**
     * 构建包含图关系的上下文
     */
    private String buildGraphContext(
            List<VectorStoreService.SearchResult> vectorResults,
            List<RagResponse.GraphRelation> graphRelations,
            String globalSummary) {
        
        StringBuilder sb = new StringBuilder();

        // 全局摘要
        if (globalSummary != null && !globalSummary.isBlank()) {
            sb.append("【全局概述】\n").append(globalSummary).append("\n\n");
        }

        // 知识图谱关系
        if (!graphRelations.isEmpty()) {
            sb.append("【知识图谱关系】\n");
            for (RagResponse.GraphRelation rel : graphRelations) {
                sb.append(String.format("- %s -[%s]-> %s\n", 
                    rel.getSourceEntity(), rel.getRelation(), rel.getTargetEntity()));
            }
            sb.append("\n");
        }

        // 文档内容
        sb.append("【相关文档】\n");
        for (VectorStoreService.SearchResult r : vectorResults) {
            sb.append(String.format("【文档 %s，相似度: %.2f】\n%s\n\n", 
                r.getId(), r.getScore(), r.getContent()));
        }

        return sb.toString();
    }

    private String buildGraphSystemPrompt() {
        return """
            你是一个专业的知识库助手，能够理解知识图谱中的实体关系。
            
            回答要求：
            1. 综合利用文档内容和知识图谱关系来回答
            2. 如果问题涉及实体间的关系，优先使用图谱信息
            3. 引用来源文档和关系
            4. 如果信息不足，明确说明
            """;
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
}

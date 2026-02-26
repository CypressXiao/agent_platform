package com.agentplatform.gateway.rag.component;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * 知识图谱存储接口
 * 支持 Neo4j 或其他图数据库
 * 默认提供内存实现，生产环境应接入真实图数据库
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent-platform.rag.graph.enabled", havingValue = "true", matchIfMissing = false)
public class GraphStore {

    @Value("${agent-platform.rag.graph.provider:memory}")
    private String provider; // memory, neo4j

    @Value("${agent-platform.rag.graph.neo4j-url:}")
    private String neo4jUrl;

    @Value("${agent-platform.rag.graph.neo4j-user:}")
    private String neo4jUser;

    @Value("${agent-platform.rag.graph.neo4j-password:}")
    private String neo4jPassword;

    // 内存存储（开发/测试用）
    private final Map<String, List<Relation>> memoryStore = new HashMap<>();
    private final Map<String, String> summaryStore = new HashMap<>();

    /**
     * 获取实体的关系
     *
     * @param tenantId 租户ID
     * @param entity 实体名称
     * @param depth 遍历深度
     * @return 关系列表
     */
    public List<Relation> getRelations(String tenantId, String entity, int depth) {
        return switch (provider.toLowerCase()) {
            case "neo4j" -> getRelationsFromNeo4j(tenantId, entity, depth);
            default -> getRelationsFromMemory(tenantId, entity, depth);
        };
    }

    /**
     * 存储关系
     */
    public void storeRelation(String tenantId, Relation relation) {
        switch (provider.toLowerCase()) {
            case "neo4j" -> storeRelationToNeo4j(tenantId, relation);
            default -> storeRelationToMemory(tenantId, relation);
        }
    }

    /**
     * 获取全局摘要
     */
    public String getGlobalSummary(String tenantId, String collection) {
        String key = tenantId + ":" + collection;
        return summaryStore.getOrDefault(key, "");
    }

    /**
     * 存储全局摘要
     */
    public void storeGlobalSummary(String tenantId, String collection, String summary) {
        String key = tenantId + ":" + collection;
        summaryStore.put(key, summary);
    }

    // ========== 内存实现 ==========

    private List<Relation> getRelationsFromMemory(String tenantId, String entity, int depth) {
        String key = tenantId + ":" + entity;
        List<Relation> directRelations = memoryStore.getOrDefault(key, List.of());
        
        if (depth <= 1) {
            return directRelations;
        }

        // 递归获取更深层的关系
        Set<String> visited = new HashSet<>();
        visited.add(entity);
        
        List<Relation> allRelations = new ArrayList<>(directRelations);
        Queue<String> queue = new LinkedList<>();
        
        for (Relation rel : directRelations) {
            if (!visited.contains(rel.getTarget())) {
                queue.add(rel.getTarget());
                visited.add(rel.getTarget());
            }
        }

        int currentDepth = 1;
        while (!queue.isEmpty() && currentDepth < depth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                String currentKey = tenantId + ":" + current;
                List<Relation> relations = memoryStore.getOrDefault(currentKey, List.of());
                
                for (Relation rel : relations) {
                    allRelations.add(rel);
                    if (!visited.contains(rel.getTarget())) {
                        queue.add(rel.getTarget());
                        visited.add(rel.getTarget());
                    }
                }
            }
            currentDepth++;
        }

        return allRelations;
    }

    private void storeRelationToMemory(String tenantId, Relation relation) {
        String key = tenantId + ":" + relation.getSource();
        memoryStore.computeIfAbsent(key, k -> new ArrayList<>()).add(relation);
    }

    // ========== Neo4j 实现 ==========

    private List<Relation> getRelationsFromNeo4j(String tenantId, String entity, int depth) {
        if (neo4jUrl == null || neo4jUrl.isBlank()) {
            log.warn("Neo4j URL not configured, falling back to memory store");
            return getRelationsFromMemory(tenantId, entity, depth);
        }

        try {
            // Cypher 查询
            String cypher = String.format("""
                MATCH (n {name: $entity, tenant_id: $tenantId})-[r*1..%d]-(m)
                RETURN n.name as source, type(r[0]) as relation, m.name as target, properties(r[0]) as props
                """, depth);

            WebClient client = WebClient.builder()
                .baseUrl(neo4jUrl)
                .defaultHeaders(headers -> {
                    String auth = Base64.getEncoder().encodeToString(
                        (neo4jUser + ":" + neo4jPassword).getBytes());
                    headers.set("Authorization", "Basic " + auth);
                })
                .build();

            Map<String, Object> request = Map.of(
                "statements", List.of(Map.of(
                    "statement", cypher,
                    "parameters", Map.of("entity", entity, "tenantId", tenantId)
                ))
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post()
                .uri("/db/neo4j/tx/commit")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("results")) {
                return parseNeo4jResults(response);
            }
        } catch (Exception e) {
            log.warn("Neo4j query failed: {}", e.getMessage());
        }

        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Relation> parseNeo4jResults(Map<String, Object> response) {
        List<Relation> relations = new ArrayList<>();
        
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) return relations;

        Map<String, Object> firstResult = results.get(0);
        List<Map<String, Object>> data = (List<Map<String, Object>>) firstResult.get("data");
        if (data == null) return relations;

        for (Map<String, Object> row : data) {
            List<Object> rowData = (List<Object>) row.get("row");
            if (rowData != null && rowData.size() >= 3) {
                relations.add(Relation.builder()
                    .source((String) rowData.get(0))
                    .relationType((String) rowData.get(1))
                    .target((String) rowData.get(2))
                    .properties(rowData.size() > 3 ? (Map<String, Object>) rowData.get(3) : Map.of())
                    .build());
            }
        }

        return relations;
    }

    private void storeRelationToNeo4j(String tenantId, Relation relation) {
        if (neo4jUrl == null || neo4jUrl.isBlank()) {
            storeRelationToMemory(tenantId, relation);
            return;
        }

        try {
            String cypher = """
                MERGE (a {name: $source, tenant_id: $tenantId})
                MERGE (b {name: $target, tenant_id: $tenantId})
                MERGE (a)-[r:%s]->(b)
                SET r += $props
                """.formatted(relation.getRelationType().toUpperCase().replace(" ", "_"));

            WebClient client = WebClient.builder()
                .baseUrl(neo4jUrl)
                .defaultHeaders(headers -> {
                    String auth = Base64.getEncoder().encodeToString(
                        (neo4jUser + ":" + neo4jPassword).getBytes());
                    headers.set("Authorization", "Basic " + auth);
                })
                .build();

            Map<String, Object> request = Map.of(
                "statements", List.of(Map.of(
                    "statement", cypher,
                    "parameters", Map.of(
                        "source", relation.getSource(),
                        "target", relation.getTarget(),
                        "tenantId", tenantId,
                        "props", relation.getProperties() != null ? relation.getProperties() : Map.of()
                    )
                ))
            );

            client.post()
                .uri("/db/neo4j/tx/commit")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        } catch (Exception e) {
            log.warn("Neo4j store failed, falling back to memory: {}", e.getMessage());
            storeRelationToMemory(tenantId, relation);
        }
    }

    /**
     * 关系数据结构
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Relation {
        private String source;
        private String relationType;
        private String target;
        private Map<String, Object> properties;
    }
}

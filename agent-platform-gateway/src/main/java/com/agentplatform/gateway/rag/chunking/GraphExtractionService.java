package com.agentplatform.gateway.rag.chunking;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.llm.LlmRouterService;
import com.agentplatform.gateway.rag.component.GraphStore;
import com.agentplatform.gateway.rag.chunking.Chunk;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图关系提取服务
 * 在文档分块存储时自动提取实体并构建关系
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GraphExtractionService {

    private final LlmRouterService llmRouterService;
    private final GraphStore graphStore;
    private final GraphExtractionConfig config;
    private final GraphExtractionMetrics metrics;

    /**
     * 从分块中提取实体并构建关系
     * 
     * @param identity 调用者身份
     * @param collection 集合名称
     * @param chunks 分块列表
     * @param profile Chunk Profile
     * @param mode 提取模式
     */
    public void extractAndStoreRelations(CallerIdentity identity, String collection, 
                                        List<Chunk> chunks, ChunkProfile profile, 
                                        GraphExtractionMode mode) {
        try {
            log.info("Starting graph extraction with mode: {} for {} chunks", mode, chunks.size());
            
            // 记录指标
            metrics.recordExtractionStart(mode, collection);
            
            switch (mode) {
                case ONTOLOGY_BASED -> extractOntologyBasedRelations(identity, collection, chunks, profile);
                case LLM_DRIVEN -> extractLlmDrivenRelations(identity, collection, chunks, profile);
                case DISABLED -> log.debug("Graph extraction is disabled");
            }
            
        } catch (Exception e) {
            log.error("Failed to extract and store graph relations with mode: {}", mode, e);
            metrics.recordExtractionFailure(mode, collection, e.getMessage());
            // 不影响主流程，只记录错误
        }
    }
    
    /**
     * 基于本体论的关系提取（新文档推荐）
     */
    private void extractOntologyBasedRelations(CallerIdentity identity, String collection, 
                                               List<Chunk> chunks, ChunkProfile profile) {
        log.info("Using ontology-based relation extraction");
        
        // 1. 从 metadata 提取结构化实体
        Set<String> entities = extractOntologyEntities(chunks, profile);
        log.info("Extracted {} ontology entities", entities.size());
        
        // 2. 基于文档结构构建关系
        List<GraphStore.Relation> relations = buildStructuralRelations(chunks, profile);
        log.info("Built {} structural relations", relations.size());
        
        // 3. 存储关系
        for (GraphStore.Relation relation : relations) {
            graphStore.storeRelation(identity.getTenantId(), relation);
        }
        
        // 4. 生成本体论摘要
        if (shouldGenerateSummary(chunks)) {
            String summary = generateOntologySummary(chunks, profile);
            graphStore.storeGlobalSummary(identity.getTenantId(), collection, summary);
        }
    }
    
    /**
     * LLM 驱动的关系提取（老文档推荐）
     */
    private void extractLlmDrivenRelations(CallerIdentity identity, String collection, 
                                          List<Chunk> chunks, ChunkProfile profile) {
        log.info("Using LLM-driven relation extraction");
        
        // 1. 使用 LLM 提取实体
        Set<String> entities = extractEntitiesWithLLM(identity, chunks);
        log.info("LLM extracted {} entities", entities.size());
        
        // 2. 使用 LLM 分析语义关系
        List<GraphStore.Relation> relations = analyzeSemanticRelationsWithLLM(identity, chunks, entities);
        log.info("LLM analyzed {} semantic relations", relations.size());
        
        // 3. 存储关系
        for (GraphStore.Relation relation : relations) {
            graphStore.storeRelation(identity.getTenantId(), relation);
        }
        
        // 4. 生成语义摘要
        if (shouldGenerateSummary(chunks)) {
            String summary = generateSemanticSummary(identity, chunks);
            graphStore.storeGlobalSummary(identity.getTenantId(), collection, summary);
        }
    }
    

    /**
     * 基于本体论提取实体
     */
    private Set<String> extractOntologyEntities(List<Chunk> chunks, ChunkProfile profile) {
        Set<String> entities = new HashSet<>();
        
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata();
            if (metadata == null) continue;
            
            // 从 profile 定义的 schema 字段中提取实体
            for (ChunkProfile.SchemaField field : profile.getSchemaFields()) {
                Object value = metadata.get(field.name());
                if (value != null) {
                    if (value instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> list = (List<String>) value;
                        entities.addAll(list);
                    } else {
                        entities.add(String.valueOf(value));
                    }
                }
            }
        }
        
        return entities.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }
    
    /**
     * 构建结构化关系
     */
    private List<GraphStore.Relation> buildStructuralRelations(List<Chunk> chunks, ChunkProfile profile) {
        List<GraphStore.Relation> relations = new ArrayList<>();
        
        // 根据不同的 profile 类型构建不同的结构化关系
        if (profile.getName().equals("sop")) {
            relations.addAll(buildSopRelations(chunks));
        } else {
            // 通用的文档结构关系
            relations.addAll(buildGenericRelations(chunks));
        }
        
        return relations;
    }
    
    /**
     * 构建 SOP 关系
     */
    private List<GraphStore.Relation> buildSopRelations(List<Chunk> chunks) {
        List<GraphStore.Relation> relations = new ArrayList<>();
        Map<String, List<Chunk>> sopChunks = new HashMap<>();
        Map<String, Map<Integer, String>> sopSteps = new HashMap<>();
        
        // 收集 SOP 和步骤信息
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata();
            if (metadata == null) continue;
            
            String sopName = (String) metadata.get("sop_name");
            if (sopName != null) {
                sopChunks.computeIfAbsent(sopName, k -> new ArrayList<>()).add(chunk);
                
                Integer stepNumber = (Integer) metadata.get("step_number");
                String stepTitle = (String) metadata.get("step_title");
                if (stepNumber != null && stepTitle != null) {
                    sopSteps.computeIfAbsent(sopName, k -> new TreeMap<>())
                           .put(stepNumber, stepTitle);
                }
            }
        }
        
        // 构建步骤间关系
        for (Map.Entry<String, Map<Integer, String>> entry : sopSteps.entrySet()) {
            String sopName = entry.getKey();
            Map<Integer, String> steps = entry.getValue();
            
            List<Integer> stepNumbers = new ArrayList<>(steps.keySet());
            for (int i = 0; i < stepNumbers.size() - 1; i++) {
                int currentStep = stepNumbers.get(i);
                int nextStep = stepNumbers.get(i + 1);
                
                String currentTitle = steps.get(currentStep);
                String nextTitle = steps.get(nextStep);
                
                relations.add(GraphStore.Relation.builder()
                    .source(currentTitle)
                    .relationType("前置步骤")
                    .target(nextTitle)
                    .properties(Map.of(
                        "sop_name", sopName,
                        "step_order", currentStep + "->" + nextStep,
                        "extraction_method", "ontology_based"
                    ))
                    .build());
            }
            
            // 构建 SOP 与步骤的关系
            for (String stepTitle : steps.values()) {
                relations.add(GraphStore.Relation.builder()
                    .source(sopName)
                    .relationType("包含步骤")
                    .target(stepTitle)
                    .properties(Map.of(
                        "extraction_method", "ontology_based"
                    ))
                    .build());
            }
        }
        
        return relations;
    }
    
    /**
     * 构建通用文档关系
     */
    private List<GraphStore.Relation> buildGenericRelations(List<Chunk> chunks) {
        List<GraphStore.Relation> relations = new ArrayList<>();
        
        // 基于标题层级的关系
        Map<Integer, String> headings = new TreeMap<>();
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata();
            if (metadata != null) {
                Integer level = (Integer) metadata.get("heading_level");
                String title = (String) metadata.get("heading_title");
                if (level != null && title != null) {
                    headings.put(level * 1000 + chunk.getIndex(), title); // 用 level 和 index 排序
                }
            }
        }
        
        // 构建标题层级关系
        List<String> sortedTitles = headings.values().stream().toList();
        for (int i = 0; i < sortedTitles.size() - 1; i++) {
            relations.add(GraphStore.Relation.builder()
                .source(sortedTitles.get(i))
                .relationType("后续内容")
                .target(sortedTitles.get(i + 1))
                .properties(Map.of(
                    "extraction_method", "ontology_based"
                ))
                .build());
        }
        
        return relations;
    }
    
    /**
     * 使用 LLM 提取实体
     */
    private Set<String> extractEntitiesWithLLM(CallerIdentity identity, List<Chunk> chunks) {
        Set<String> entities = new HashSet<>();
        
        try {
            String systemPrompt = """
                你是一个实体识别专家。请从提供的文档内容中提取重要的实体。
                
                实体类型包括：
                1. 人物角色（如：员工、经理、审批人）
                2. 部门组织（如：人事部、财务部）
                3. 文档概念（如：SOP、流程、规定）
                4. 具体物品（如：材料、设备、表格）
                5. 时间地点（如：入职时间、办公室）
                
                输出格式：每行一个实体，不需要编号或解释。
                """;
            
            StringBuilder content = new StringBuilder();
            content.append("文档内容：\n");
            for (Chunk chunk : chunks.stream().limit(8).collect(Collectors.toList())) {
                content.append(chunk.getContent().substring(0, Math.min(500, chunk.getContent().length()))).append("\n\n");
            }
            
            Map<String, Object> result = llmRouterService.chat(
                identity,
                "default",
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", content.toString())
                ),
                0.1,
                300
            );
            
            String response = (String) result.get("content");
            if (response != null) {
                entities = Arrays.stream(response.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            }
            
        } catch (Exception e) {
            log.warn("Failed to extract entities with LLM: {}", e.getMessage());
        }
        
        return entities;
    }
    
    /**
     * 使用 LLM 分析语义关系
     */
    private List<GraphStore.Relation> analyzeSemanticRelationsWithLLM(CallerIdentity identity,
                                                                   List<Chunk> chunks,
                                                                   Set<String> entities) {
        List<GraphStore.Relation> relations = new ArrayList<>();
        
        if (entities.size() < 2) {
            return relations;
        }
        
        try {
            String systemPrompt = """
                你是一个语义关系分析专家。请分析实体间的关系。
                
                关系类型：
                - 依赖关系：A依赖B
                - 包含关系：A包含B  
                - 相关关系：A与B相关
                - 因果关系：A导致B
                - 条件关系：A是B的条件
                
                输出格式：源实体 -> 关系类型 -> 目标实体
                """;
            
            StringBuilder content = new StringBuilder();
            content.append("实体列表：\n");
            entities.forEach(entity -> content.append("- ").append(entity).append("\n"));
            content.append("\n文档内容：\n");
            for (Chunk chunk : chunks.stream().limit(5).collect(Collectors.toList())) {
                content.append(chunk.getContent().substring(0, Math.min(300, chunk.getContent().length()))).append("\n\n");
            }
            
            Map<String, Object> result = llmRouterService.chat(
                identity,
                "default",
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", content.toString())
                ),
                0.3,
                500
            );
            
            String response = (String) result.get("content");
            if (response != null) {
                relations = parseSemanticRelations(response);
                // 标记为 LLM 驱动
                relations = relations.stream()
                    .map(rel -> GraphStore.Relation.builder()
                        .source(rel.getSource())
                        .relationType(rel.getRelationType())
                        .target(rel.getTarget())
                        .properties(Map.of(
                            "extraction_method", "llm_driven",
                            "confidence", 0.7
                        ))
                        .build())
                    .collect(Collectors.toList());
            }
            
        } catch (Exception e) {
            log.warn("Failed to analyze semantic relations with LLM: {}", e.getMessage());
        }
        
        return relations;
    }
    
    /**
     * 判断是否需要生成摘要
     */
    private boolean shouldGenerateSummary(List<Chunk> chunks) {
        return chunks.size() >= 3;
    }
    
    /**
     * 生成本体论摘要
     */
    private String generateOntologySummary(List<Chunk> chunks, ChunkProfile profile) {
        try {
            String systemPrompt = String.format("""
                你是一个文档摘要专家。请基于%s类型的文档结构生成摘要。
                
                要求：
                1. 突出文档的主要结构和流程
                2. 保持逻辑清晰
                3. 长度控制在200-300字
                """, profile.getName());
            
            StringBuilder content = new StringBuilder();
            for (Chunk chunk : chunks.stream().limit(10).collect(Collectors.toList())) {
                String chunkContent = chunk.getContent().substring(0, Math.min(200, chunk.getContent().length()));
                content.append("【").append(chunkContent).append("】\n\n");
            }
            
            Map<String, Object> result = llmRouterService.chat(
                null, // 不需要 identity
                "default",
                List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", content.toString())
                ),
                0.5,
                600
            );
            
            return (String) result.get("content");
            
        } catch (Exception e) {
            log.warn("Failed to generate ontology summary: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * 生成语义摘要
     */
    private String generateSemanticSummary(CallerIdentity identity, List<Chunk> chunks) {
        // 类似 generateOntologySummary 但针对语义分析
        return generateOntologySummary(chunks, null); // 复用逻辑
    }
    
    
    /**
     * 解析语义关系
     */
    private List<GraphStore.Relation> parseSemanticRelations(String response) {
        List<GraphStore.Relation> relations = new ArrayList<>();
        
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("->")) {
                String[] parts = line.split("->");
                if (parts.length == 3) {
                    String source = parts[0].trim();
                    String relationType = parts[1].trim();
                    String target = parts[2].trim();
                    
                    if (!source.isEmpty() && !relationType.isEmpty() && !target.isEmpty()) {
                        relations.add(GraphStore.Relation.builder()
                            .source(source)
                            .relationType(relationType)
                            .target(target)
                            .properties(Map.of(
                                "extraction_method", "semantic",
                                "confidence", 0.8
                            ))
                            .build());
                    }
                }
            }
        }
        
        return relations;
    }
}

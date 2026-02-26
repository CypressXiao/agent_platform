package com.agentplatform.gateway.rag.component;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文补全器
 * 用于 SOP 场景，自动补充检索到的步骤的前后步骤
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContextCompleter {

    private final VectorStoreService vectorStoreService;

    @Value("${agent-platform.rag.context-completion.enabled:true}")
    private boolean enabled;

    @Value("${agent-platform.rag.context-completion.window-size:1}")
    private int windowSize; // 前后各补充几个步骤

    /**
     * 补全上下文
     * 对于 SOP 类文档，自动补充检索到的步骤的前后步骤
     *
     * @param identity 调用者身份
     * @param collection 集合名称
     * @param results 原始检索结果
     * @return 补全后的结果（包含前后步骤）
     */
    public List<VectorStoreService.SearchResult> complete(
            CallerIdentity identity,
            String collection,
            List<VectorStoreService.SearchResult> results) {
        
        if (!enabled || results.isEmpty()) {
            return results;
        }

        // 用于去重和排序
        Map<String, VectorStoreService.SearchResult> resultMap = new LinkedHashMap<>();
        
        // 先添加原始结果
        for (VectorStoreService.SearchResult result : results) {
            resultMap.put(result.getId(), result);
        }

        // 对每个结果，尝试补充前后步骤
        for (VectorStoreService.SearchResult result : results) {
            Map<String, Object> metadata = result.getMetadata();
            if (metadata == null) continue;

            // 检查是否是 SOP 步骤类型
            String chunkType = (String) metadata.get("chunk_type");
            if (!"step".equals(chunkType)) continue;

            String sopName = (String) metadata.get("sop_name");
            Object stepNumberObj = metadata.get("step_number");
            if (sopName == null || stepNumberObj == null) continue;

            int stepNumber = ((Number) stepNumberObj).intValue();

            // 补充前后步骤
            for (int offset = -windowSize; offset <= windowSize; offset++) {
                if (offset == 0) continue; // 跳过当前步骤
                
                int targetStep = stepNumber + offset;
                if (targetStep < 1) continue;

                try {
                    List<VectorStoreService.SearchResult> adjacentResults = 
                        searchByStepNumber(identity, collection, sopName, targetStep);
                    
                    for (VectorStoreService.SearchResult adjacent : adjacentResults) {
                        if (!resultMap.containsKey(adjacent.getId())) {
                            resultMap.put(adjacent.getId(), adjacent);
                            log.debug("Added adjacent step {} for SOP {}", targetStep, sopName);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to fetch adjacent step {}: {}", targetStep, e.getMessage());
                }
            }
        }

        // 按 SOP 名称和步骤号排序
        return resultMap.values().stream()
            .sorted(this::compareByStepOrder)
            .collect(Collectors.toList());
    }

    /**
     * 根据步骤号搜索
     */
    private List<VectorStoreService.SearchResult> searchByStepNumber(
            CallerIdentity identity,
            String collection,
            String sopName,
            int stepNumber) {
        
        // 构造查询：SOP名称 + 步骤号
        String query = String.format("%s 步骤%d", sopName, stepNumber);
        
        List<VectorStoreService.SearchResult> results = vectorStoreService.search(
            identity, collection, query, 3, 0.5);
        
        // 过滤出精确匹配的步骤
        return results.stream()
            .filter(r -> {
                Map<String, Object> meta = r.getMetadata();
                if (meta == null) return false;
                
                String sop = (String) meta.get("sop_name");
                Object step = meta.get("step_number");
                
                return sopName.equals(sop) && 
                       step != null && 
                       ((Number) step).intValue() == stepNumber;
            })
            .collect(Collectors.toList());
    }

    /**
     * 按 SOP 名称和步骤号排序
     */
    private int compareByStepOrder(VectorStoreService.SearchResult a, VectorStoreService.SearchResult b) {
        Map<String, Object> metaA = a.getMetadata();
        Map<String, Object> metaB = b.getMetadata();

        if (metaA == null && metaB == null) return 0;
        if (metaA == null) return 1;
        if (metaB == null) return -1;

        // 先按 SOP 名称排序
        String sopA = (String) metaA.getOrDefault("sop_name", "");
        String sopB = (String) metaB.getOrDefault("sop_name", "");
        int sopCompare = sopA.compareTo(sopB);
        if (sopCompare != 0) return sopCompare;

        // 再按步骤号排序
        Object stepA = metaA.get("step_number");
        Object stepB = metaB.get("step_number");
        
        int numA = stepA != null ? ((Number) stepA).intValue() : Integer.MAX_VALUE;
        int numB = stepB != null ? ((Number) stepB).intValue() : Integer.MAX_VALUE;
        
        return Integer.compare(numA, numB);
    }
}

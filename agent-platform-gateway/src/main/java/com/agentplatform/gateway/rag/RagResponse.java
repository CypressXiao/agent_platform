package com.agentplatform.gateway.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RAG 查询响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 生成的答案
     */
    private String answer;
    
    /**
     * 来源文档
     */
    private List<Source> sources;
    
    /**
     * 使用的模型
     */
    private String model;
    
    /**
     * Token 用量
     */
    private Map<String, Object> usage;
    
    /**
     * 使用的 RAG 模式
     */
    private RagMode mode;
    
    /**
     * 改写后的查询（如果启用了查询改写）
     */
    private String rewrittenQuery;
    
    /**
     * 是否为纯检索模式（无 LLM 答案生成）
     */
    private Boolean searchOnlyMode;
    
    /**
     * 检索统计信息
     */
    private SearchStats searchStats;
    
    /**
     * 图关系（GraphRAG 模式）
     */
    private List<GraphRelation> graphRelations;
    
    /**
     * 来源文档
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String id;
        private double score;
        private String contentPreview;
        private Map<String, Object> metadata;
        private Double rerankScore;
    }
    
    /**
     * 图关系（GraphRAG 模式）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphRelation {
        private String sourceEntity;
        private String relation;
        private String targetEntity;
        private Map<String, Object> properties;
    }
    
    /**
     * 检索统计信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchStats {
        /**
         * 初始检索数量
         */
        private Integer initialRetrieved;
        
        /**
         * 重排序后数量
         */
        private Integer rerankedCount;
        
        /**
         * 上下文补全后数量
         */
        private Integer contextCompletedCount;
        
        /**
         * 是否启用混合检索
         */
        private Boolean hybridSearch;
        
        /**
         * 检索耗时（毫秒）
         */
        private Long searchDurationMs;
        
        /**
         * 重排序耗时（毫秒）
         */
        private Long rerankDurationMs;
        
        /**
         * 上下文补全耗时（毫秒）
         */
        private Long contextCompletionDurationMs;
        
        /**
         * 使用的检索类型
         */
        private String searchType;
        
        /**
         * 相似度阈值
         */
        private Double similarityThreshold;
    }
}

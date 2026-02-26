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
}

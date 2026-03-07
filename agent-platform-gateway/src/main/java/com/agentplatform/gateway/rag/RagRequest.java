package com.agentplatform.gateway.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 查询请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRequest {
    
    /**
     * 集合名称
     */
    private String collection;
    
    /**
     * 用户查询
     */
    private String query;
    
    /**
     * RAG 模式，默认 ADVANCED
     */
    @Builder.Default
    private RagMode mode = RagMode.ADVANCED;
    
    /**
     * 检索数量
     */
    @Builder.Default
    private int topK = 5;
    
    /**
     * 相似度阈值
     */
    @Builder.Default
    private double similarityThreshold = 0.7;
    
    /**
     * LLM 模型
     */
    @Builder.Default
    private String model = "default";
    
    /**
     * 自定义系统提示词
     */
    private String systemPrompt;
    
    /**
     * 是否启用查询改写
     */
    @Builder.Default
    private boolean enableQueryRewrite = true;
    
    /**
     * 是否启用重排序
     */
    @Builder.Default
    private boolean enableRerank = true;
    
    /**
     * 是否启用上下文补全（SOP 场景）
     */
    @Builder.Default
    private boolean enableContextCompletion = true;
    
    /**
     * 是否启用 LLM 答案生成（默认 false，仅返回检索结果）
     */
    @Builder.Default
    private boolean enableAnswerLLM = false;
    
    /**
     * 元数据过滤条件
     */
    private Map<String, Object> metadataFilter;
    
    /**
     * GraphRAG 专用：图遍历深度
     */
    @Builder.Default
    private int graphDepth = 2;
    
    /**
     * GraphRAG 专用：是否包含全局摘要
     */
    @Builder.Default
    private boolean includeGlobalSummary = false;
}

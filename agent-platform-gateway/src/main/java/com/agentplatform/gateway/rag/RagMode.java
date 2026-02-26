package com.agentplatform.gateway.rag;

/**
 * RAG 模式枚举
 */
public enum RagMode {
    /**
     * 基础 RAG：向量检索 → 拼接 → 生成
     */
    NAIVE,
    
    /**
     * 高级 RAG：查询改写 + 混合检索 + 重排序 + 上下文补全
     */
    ADVANCED,
    
    /**
     * 图 RAG：结合知识图谱的 RAG
     */
    GRAPH
}

package com.agentplatform.gateway.rag.chunking;

/**
 * 分块策略枚举
 */
public enum ChunkingStrategy {
    /**
     * 固定大小切分
     * 按字符数切分，支持重叠
     */
    FIXED_SIZE,
    
    /**
     * 递归切分
     * 按分隔符递归切分，尽量保持段落完整
     */
    RECURSIVE,
    
    /**
     * Agentic Chunking
     * LLM 辅助切分，需要 LLM 模型分析文档结构
     */
    AGENTIC,
    
    /**
     * 语义切分
     * 按语义相似度切分，需要 Embedding 模型
     */
    SEMANTIC,
    
    /**
     * 结构切分（推荐用于 SOP）
     * 按文档结构（标题、步骤、FAQ）切分
     */
    DOCUMENT_BASED,
    
    /**
     * Markdown 专用切分
     * 按 Markdown 标题层级切分
     */
    MARKDOWN
}

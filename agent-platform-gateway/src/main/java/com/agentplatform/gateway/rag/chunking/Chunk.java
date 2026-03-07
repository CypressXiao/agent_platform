package com.agentplatform.gateway.rag.chunking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分块结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {
    
    /**
     * Chunk ID（自动生成）
     */
    private String id;
    
    /**
     * Chunk 内容
     */
    private String content;
    
    /**
     * Chunk 类型
     */
    private ChunkType type;
    
    /**
     * 在原文档中的位置（字符偏移）
     */
    private int startOffset;
    
    /**
     * 在原文档中的结束位置
     */
    private int endOffset;
    
    /**
     * Chunk 序号（在文档中的顺序）
     */
    private int index;
    
    /**
     * 元数据
     */
    private Map<String, Object> metadata;
    
    /**
     * 提取的关键词
     */
    private List<String> keywords;
    
    /**
     * 稠密向量（embedding）
     * 由 VectorStoreService 在存储时生成
     */
    private float[] denseVector;
    
    /**
     * Chunk 类型枚举
     */
    public enum ChunkType {
        /**
         * 概述/摘要
         */
        OVERVIEW,
        
        /**
         * 步骤
         */
        STEP,
        
        /**
         * FAQ 问答
         */
        FAQ,
        
        /**
         * 附录
         */
        APPENDIX,
        
        /**
         * 普通段落
         */
        PARAGRAPH,
        
        /**
         * 标题
         */
        HEADING,
        
        /**
         * 表格
         */
        TABLE,
        
        /**
         * 代码块
         */
        CODE
    }
}

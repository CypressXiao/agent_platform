package com.agentplatform.gateway.rag.chunking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分块配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkingConfig {
    
    /**
     * 分块策略
     */
    @Builder.Default
    private ChunkingStrategy strategy = ChunkingStrategy.MARKDOWN;
    
    /**
     * 目标 chunk 大小（字符数）
     */
    @Builder.Default
    private int chunkSize = 500;
    
    /**
     * 最小 chunk 大小，小于此值会合并
     */
    @Builder.Default
    private int minChunkSize = 100;
    
    /**
     * 最大 chunk 大小，大于此值会递归切分
     */
    @Builder.Default
    private int maxChunkSize = 1500;
    
    /**
     * 重叠大小（字符数），仅 FIXED_SIZE 策略使用
     */
    @Builder.Default
    private int overlap = 100;
    
    /**
     * 分隔符列表，按优先级排序
     */
    @Builder.Default
    private List<String> separators = List.of(
        "### ",      // Markdown H3
        "## ",       // Markdown H2
        "# ",        // Markdown H1
        "\n\n",      // 段落
        "\n",        // 换行
        "。",        // 中文句号
        ". ",        // 英文句号
        " "          // 空格
    );
    
    /**
     * SOP 步骤标题模式
     */
    @Builder.Default
    private String stepPattern = "^###\\s*步骤\\s*(\\d+)";
    
    /**
     * FAQ 问题模式
     */
    @Builder.Default
    private String faqPattern = "^###\\s*Q[:：]";
    
    /**
     * 是否提取关键词
     */
    @Builder.Default
    private boolean extractKeywords = true;
    
    /**
     * 关键词最大数量
     */
    @Builder.Default
    private int maxKeywords = 10;
    
    /**
     * 语义切分阈值（相似度低于此值时切分）
     */
    @Builder.Default
    private double semanticThreshold = 0.5;
    
    /**
     * Agentic Chunking 使用的 LLM 模型
     */
    private String agenticModel;
    
    /**
     * Embedding 模型名称
     * 重要：写入和查询必须使用相同的模型，否则向量空间不一致
     */
    @Builder.Default
    private String embeddingModel = "bge-large-zh";
    
    /**
     * Embedding 模型最大 token 数
     * 常见模型限制：
     * - OpenAI text-embedding-3-small/large: 8191 tokens
     * - OpenAI text-embedding-ada-002: 8191 tokens
     * - BGE-large-zh: 512 tokens
     * - BGE-m3: 8192 tokens
     * - Cohere embed-v3: 512 tokens
     */
    @Builder.Default
    private int embeddingMaxTokens = 512;
    
    /**
     * 每个 token 大约对应的字符数（用于估算）
     * 中文约 1.5 字符/token，英文约 4 字符/token
     * 混合内容取保守值 2
     */
    @Builder.Default
    private double charsPerToken = 2.0;
    
    /**
     * 根据 embedding 模型限制计算最大字符数
     */
    public int getMaxCharsForEmbedding() {
        return (int) (embeddingMaxTokens * charsPerToken * 0.9); // 留 10% 余量
    }
    
    /**
     * 预设配置：SOP 文档
     */
    public static ChunkingConfig forSOP() {
        return ChunkingConfig.builder()
            .strategy(ChunkingStrategy.MARKDOWN)
            .chunkSize(500)
            .minChunkSize(100)
            .maxChunkSize(1500)
            .overlap(0)
            .extractKeywords(true)
            .build();
    }
    
    /**
     * 预设配置：通用文档
     */
    public static ChunkingConfig forGeneral() {
        return ChunkingConfig.builder()
            .strategy(ChunkingStrategy.RECURSIVE)
            .chunkSize(500)
            .minChunkSize(100)
            .maxChunkSize(1000)
            .overlap(100)
            .extractKeywords(true)
            .build();
    }
    
    /**
     * 预设配置：长文档
     */
    public static ChunkingConfig forLongDocument() {
        return ChunkingConfig.builder()
            .strategy(ChunkingStrategy.FIXED_SIZE)
            .chunkSize(1000)
            .minChunkSize(200)
            .maxChunkSize(2000)
            .overlap(200)
            .extractKeywords(true)
            .build();
    }
}

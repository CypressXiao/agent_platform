package com.agentplatform.gateway.rag.chunking.profile.impl;

import com.agentplatform.gateway.rag.chunking.ChunkingConfig;
import com.agentplatform.gateway.rag.chunking.ChunkingConfig.KeywordExtractionStrategy;
import com.agentplatform.gateway.rag.chunking.ChunkingStrategy;
import com.agentplatform.gateway.rag.chunking.profile.AbstractChunkProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 非标准文档 Profile
 * 适用于历史文档、无结构化 metadata 的文档
 * 策略：启用稀疏向量 + 关键词提取，通过稠密向量 + 稀疏向量混合检索提升效果
 */
@Component
public class LegacyChunkProfile extends AbstractChunkProfile {

    @Override
    public String getName() {
        return "legacy";
    }

    @Override
    public String getDescription() {
        return "非标准文档 Profile，适用于历史文档、无结构化 metadata 的文档";
    }

    @Override
    public ChunkingConfig getChunkingConfig() {
        // 非标准文档：启用稀疏向量 + 关键词提取，使用混合检索
        return ChunkingConfig.builder()
            .strategy(ChunkingStrategy.RECURSIVE) // 使用递归切分，更适合无结构的文档
            .chunkSize(600)
            .minChunkSize(150)
            .maxChunkSize(1200)
            .overlap(100)
            .keywordStrategy(KeywordExtractionStrategy.MILVUS_ANALYZER) // 使用 Milvus analyzer 提取关键词
            .enableSparseVector(true) // 启用稀疏向量
            .sparseAnalyzer("chinese") // 使用中文分词器
            .build();
    }

    @Override
    public List<SchemaField> getSchemaFields() {
        return List.of(
            // 基础文档信息（可能为空）
            new SchemaField("document_id", SchemaField.FieldType.STRING, true, "文档唯一标识（系统自动生成）", null),
            new SchemaField("document_name", SchemaField.FieldType.STRING, true, "文档名称", null),
            new SchemaField("version", SchemaField.FieldType.STRING, false, "文档版本号", null),
            new SchemaField("publish_date", SchemaField.FieldType.STRING, false, "发布日期", null),
            new SchemaField("owner", SchemaField.FieldType.STRING, false, "负责人", null),
            new SchemaField("applicable_scope", SchemaField.FieldType.STRING, false, "适用范围", null),
            new SchemaField("tags", SchemaField.FieldType.LIST, false, "标签列表（可能为空）", List.of()),
            // 分块相关字段
            new SchemaField("chunk_index", SchemaField.FieldType.INTEGER, false, "分块序号", 0),
            new SchemaField("chunk_type", SchemaField.FieldType.STRING, false, "分块类型（paragraph）", "paragraph"),
            new SchemaField("heading_level", SchemaField.FieldType.INTEGER, false, "标题层级", null),
            new SchemaField("heading_title", SchemaField.FieldType.STRING, false, "标题内容", null),
            // 来源信息
            new SchemaField("source", SchemaField.FieldType.STRING, false, "文档来源", null)
        );
    }

    @Override
    public String getCollectionPattern() {
        return "legacy_{model}";
    }

    @Override
    public Map<String, Object> enrichMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        Map<String, Object> enriched = super.enrichMetadata(metadata, context);
        
        // 自动生成 document_id（如果未提供）
        if (!enriched.containsKey("document_id") || enriched.get("document_id") == null) {
            String docName = (String) enriched.getOrDefault("document_name", "legacy");
            String docId = generateDocumentId("LEGACY", docName);
            enriched.put("document_id", docId);
        }
        
        // 标记为 legacy 文档
        enriched.put("document_type", "legacy");
        
        // 标记 profile
        enriched.put("_profile", getName());
        
        return enriched;
    }

    /**
     * 生成文档 ID
     * 格式：{prefix}-{简化文档名}-{时间戳后6位}
     */
    private String generateDocumentId(String prefix, String docName) {
        String simpleName = docName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "")
            .substring(0, Math.min(docName.length(), 10));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return String.format("%s-%s-%s", prefix, simpleName, suffix);
    }
}

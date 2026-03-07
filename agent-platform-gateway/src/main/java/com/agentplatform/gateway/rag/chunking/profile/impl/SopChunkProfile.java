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
 * SOP 文档 Profile
 * 对应 document-format-guide.md 中的"SOP 文档"模板
 * 适用于标准操作流程、规章制度、工作指南等
 */
@Component
public class SopChunkProfile extends AbstractChunkProfile {

    @Override
    public String getName() {
        return "sop";
    }

    @Override
    public String getDescription() {
        return "SOP 文档 Profile，适用于标准操作流程、规章制度、工作指南等";
    }

    @Override
    public ChunkingConfig getChunkingConfig() {
        // 标准文档：依赖作者提供的 tags，不自动提取关键词，不启用稀疏向量
        return ChunkingConfig.builder()
            .strategy(ChunkingStrategy.MARKDOWN)
            .chunkSize(500)
            .minChunkSize(100)
            .maxChunkSize(1500)
            .overlap(0)  // SOP 步骤通常不需要重叠
            .keywordStrategy(KeywordExtractionStrategy.DISABLED)
            .enableSparseVector(false)
            .build();
    }

    @Override
    public List<SchemaField> getSchemaFields() {
        return List.of(
            // 元信息块字段
            new SchemaField("document_id", SchemaField.FieldType.STRING, true, "文档唯一标识（系统自动生成或手动填写）", null),
            new SchemaField("document_name", SchemaField.FieldType.STRING, true, "文档名称/SOP名称", null),
            new SchemaField("version", SchemaField.FieldType.STRING, false, "文档版本号", "v1.0"),
            new SchemaField("publish_date", SchemaField.FieldType.STRING, false, "发布日期", null),
            new SchemaField("owner", SchemaField.FieldType.STRING, false, "负责人", null),
            new SchemaField("approver", SchemaField.FieldType.STRING, false, "审批人", null),
            new SchemaField("applicable_scope", SchemaField.FieldType.STRING, false, "适用范围", null),
            new SchemaField("tags", SchemaField.FieldType.LIST, false, "标签列表", List.of()),
            // 分块相关字段
            new SchemaField("chunk_index", SchemaField.FieldType.INTEGER, false, "分块序号", 0),
            new SchemaField("chunk_type", SchemaField.FieldType.STRING, false, "分块类型（overview/step/faq/appendix）", "paragraph"),
            new SchemaField("heading_level", SchemaField.FieldType.INTEGER, false, "标题层级", null),
            new SchemaField("heading_title", SchemaField.FieldType.STRING, false, "标题内容", null),
            new SchemaField("sop_name", SchemaField.FieldType.STRING, false, "SOP 名称（从一级标题提取）", null),
            // SOP 特有字段
            new SchemaField("step_number", SchemaField.FieldType.INTEGER, false, "步骤编号", null),
            new SchemaField("step_title", SchemaField.FieldType.STRING, false, "步骤标题", null),
            new SchemaField("question", SchemaField.FieldType.STRING, false, "FAQ 问题", null),
            new SchemaField("keywords", SchemaField.FieldType.LIST, false, "关键词列表", List.of())
        );
    }

    @Override
    public String getCollectionPattern() {
        return "sop_{model}";
    }

    @Override
    public Map<String, Object> enrichMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        Map<String, Object> enriched = super.enrichMetadata(metadata, context);
        
        // 自动生成 document_id（如果未提供）
        if (!enriched.containsKey("document_id") || enriched.get("document_id") == null) {
            String docName = (String) enriched.getOrDefault("document_name", "sop");
            String docId = generateDocumentId("SOP", docName);
            enriched.put("document_id", docId);
        }
        
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

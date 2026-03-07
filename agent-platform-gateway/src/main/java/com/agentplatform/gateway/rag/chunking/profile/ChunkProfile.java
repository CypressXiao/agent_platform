package com.agentplatform.gateway.rag.chunking.profile;

import com.agentplatform.gateway.rag.chunking.ChunkingConfig;

import java.util.List;
import java.util.Map;

/**
 * Chunk Profile 接口
 * 定义业务线的分块策略、schema 字段、metadata 映射规则
 *
 * 使用场景：
 *   - 新文档：按标准模板写入，使用对应 profile 的 schema
 *   - 历史文档：通过抽取管道归一化到 profile 定义的 schema
 */
public interface ChunkProfile {

    /**
     * Profile 名称（唯一标识）
     * 例如：product_doc, faq, regulation
     */
    String getName();

    /**
     * Profile 描述
     */
    String getDescription();

    /**
     * 获取分块配置
     */
    ChunkingConfig getChunkingConfig();

    /**
     * 获取 schema 定义的字段列表
     * 这些字段会被写入 metadata，并影响 collection schema
     */
    List<SchemaField> getSchemaFields();

    /**
     * 获取目标 collection 命名模式
     * 统一格式：{tenant}_{scene}_{profile}_{model}
     */
    default String getCollectionPattern() {
        return "{tenant}_{scene}_{profile}_{model}";
    }

    /**
     * 对原始 metadata 进行增强/映射
     * 在 chunk 完成后、写入 vector store 前调用
     *
     * @param rawMetadata 原始 metadata（来自 chunker）
     * @param context 上下文信息（如文档名、来源等）
     * @return 增强后的 metadata
     */
    Map<String, Object> enrichMetadata(Map<String, Object> rawMetadata, Map<String, Object> context);

    /**
     * 校验 metadata 是否符合 schema 要求
     *
     * @param metadata 待校验的 metadata
     * @return 校验结果
     */
    ValidationResult validateMetadata(Map<String, Object> metadata);

    /**
     * Schema 字段定义
     */
    record SchemaField(
        String name,
        FieldType type,
        boolean required,
        String description,
        Object defaultValue
    ) {
        public enum FieldType {
            STRING, INTEGER, FLOAT, BOOLEAN, LIST, MAP
        }
    }

    /**
     * 校验结果
     */
    record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}

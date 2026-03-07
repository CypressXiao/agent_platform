package com.agentplatform.gateway.rag.chunking.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChunkProfile 抽象基类
 * 提供通用的 metadata 校验和增强逻辑
 */
public abstract class AbstractChunkProfile implements ChunkProfile {

    @Override
    public Map<String, Object> enrichMetadata(Map<String, Object> rawMetadata, Map<String, Object> context) {
        Map<String, Object> enriched = new HashMap<>(rawMetadata != null ? rawMetadata : Map.of());

        // 添加 profile 标识
        enriched.put("_profile", getName());

        // 填充默认值
        for (SchemaField field : getSchemaFields()) {
            if (!enriched.containsKey(field.name()) && field.defaultValue() != null) {
                enriched.put(field.name(), field.defaultValue());
            }
        }

        // 从 context 中提取常用字段
        if (context != null) {
            if (context.containsKey("documentName") && !enriched.containsKey("document_name")) {
                enriched.put("document_name", context.get("documentName"));
            }
            if (context.containsKey("source") && !enriched.containsKey("source")) {
                enriched.put("source", context.get("source"));
            }
        }

        // 子类可覆盖此方法添加业务特定逻辑
        return doEnrichMetadata(enriched, context);
    }

    /**
     * 子类可覆盖此方法添加业务特定的 metadata 增强逻辑
     */
    protected Map<String, Object> doEnrichMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        return metadata;
    }

    @Override
    public ValidationResult validateMetadata(Map<String, Object> metadata) {
        List<String> errors = new ArrayList<>();

        for (SchemaField field : getSchemaFields()) {
            if (field.required()) {
                if (!metadata.containsKey(field.name()) || metadata.get(field.name()) == null) {
                    errors.add("Missing required field: " + field.name());
                    continue;
                }
            }

            Object value = metadata.get(field.name());
            if (value != null && !isTypeMatch(value, field.type())) {
                errors.add("Field '" + field.name() + "' type mismatch: expected " + field.type() + ", got " + value.getClass().getSimpleName());
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private boolean isTypeMatch(Object value, SchemaField.FieldType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case FLOAT -> value instanceof Float || value instanceof Double || value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case LIST -> value instanceof List;
            case MAP -> value instanceof Map;
        };
    }
}

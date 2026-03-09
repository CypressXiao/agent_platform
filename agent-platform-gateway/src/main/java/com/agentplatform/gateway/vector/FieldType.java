package com.agentplatform.gateway.vector;

/**
 * Milvus 字段类型定义
 */
public enum FieldType {
    BOOL,
    INT8,
    INT16,
    INT32,
    INT64,
    FLOAT,
    DOUBLE,
    STRING,
    VARCHAR,
    JSON,
    BINARY_VECTOR,
    FLOAT_VECTOR,
    FLOAT16_VECTOR,
    BFLOAT16_VECTOR,
    SPARSE_FLOAT_VECTOR;

    public String toMilvusType() {
        return switch (this) {
            case BOOL -> "Bool";
            case INT8 -> "Int8";
            case INT16 -> "Int16";
            case INT32 -> "Int32";
            case INT64 -> "Int64";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case STRING, VARCHAR -> "VarChar";
            case JSON -> "JSON";
            case BINARY_VECTOR -> "BinaryVector";
            case FLOAT_VECTOR -> "FloatVector";
            case FLOAT16_VECTOR -> "Float16Vector";
            case BFLOAT16_VECTOR -> "BFloat16Vector";
            case SPARSE_FLOAT_VECTOR -> "SparseFloatVector";
        };
    }
}

package com.agentplatform.gateway.rag.chunking;

/**
 * 图关系提取模式
 */
public enum GraphExtractionMode {
    /**
     * 禁用图关系提取
     */
    DISABLED("disabled", "禁用图关系提取"),
    
    /**
     * 基于本体论的标准构建
     * 完全基于 ChunkProfile 定义的 schema 和业务规则，无需 LLM
     */
    ONTOLOGY_BASED("ontology_based", "基于本体论的标准构建"),
    
    /**
     * LLM 驱动的自动生成
     * 完全使用大模型提取实体和生成关系
     */
    LLM_DRIVEN("llm_driven", "LLM驱动的自动生成");
    
    private final String code;
    private final String description;
    
    GraphExtractionMode(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 从字符串解析模式
     */
    public static GraphExtractionMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        
        return switch (value.toLowerCase().trim()) {
            case "ontology_based", "ontology" -> ONTOLOGY_BASED;
            case "llm_driven", "llm" -> LLM_DRIVEN;
            default -> DISABLED;
        };
    }
}

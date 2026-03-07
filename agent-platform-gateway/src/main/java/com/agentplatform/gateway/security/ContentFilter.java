package com.agentplatform.gateway.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 内容过滤结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentFilter {

    /**
     * 是否通过过滤
     */
    private boolean passed;

    /**
     * 过滤原因
     */
    private String reason;

    /**
     * 风险等级：low / medium / high / critical
     */
    private RiskLevel riskLevel;

    /**
     * 检测到的问题类型
     */
    private List<String> detectedIssues;

    /**
     * 脱敏后的内容（如果适用）
     */
    private String sanitizedContent;

    /**
     * 检测到的 PII 类型
     */
    private List<String> piiTypes;

    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public static ContentFilter passed() {
        return ContentFilter.builder()
            .passed(true)
            .riskLevel(RiskLevel.LOW)
            .build();
    }

    public static ContentFilter blocked(String reason, RiskLevel level) {
        return ContentFilter.builder()
            .passed(false)
            .reason(reason)
            .riskLevel(level)
            .build();
    }
}

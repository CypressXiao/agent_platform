package com.agentplatform.gateway.planner.strategy;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * ReAct 策略配置 - SDK 用户可自定义
 */
@Data
@Builder
public class ReActConfig {
    
    /**
     * LLM 模型名称
     */
    @Builder.Default
    private String model = "default";
    
    /**
     * LLM 温度参数
     */
    @Builder.Default
    private double temperature = 0.2;
    
    /**
     * 最大循环次数
     */
    @Builder.Default
    private int maxIterations = 10;
    
    /**
     * 是否启用记忆
     */
    @Builder.Default
    private boolean memoryEnabled = true;
    
    /**
     * 记忆查询模式：recent / semantic / entity
     */
    @Builder.Default
    private String memoryQueryMode = "semantic";
    
    /**
     * 记忆查询数量
     */
    @Builder.Default
    private int memoryQueryLimit = 5;
    
    /**
     * 是否在完成后保存摘要到长期记忆
     */
    @Builder.Default
    private boolean saveSummaryToMemory = true;
    
    /**
     * 自定义 System Prompt（可选）
     * 如果为 null，使用默认 Prompt
     */
    private String customSystemPrompt;
    
    /**
     * 自定义 System Prompt 生成器（可选）
     * 参数：可用工具列表
     * 返回：System Prompt
     */
    private Function<java.util.List<String>, String> systemPromptBuilder;
    
    /**
     * 自定义 User Prompt 生成器（可选）
     * 参数：(goal, context)
     * 返回：User Prompt
     */
    private BiFunction<String, PlanContext, String> userPromptBuilder;
    
    /**
     * 自定义响应解析器（可选）
     * 参数：LLM 输出
     * 返回：解析后的步骤 Map
     */
    private Function<String, Map<String, Object>> responseParser;
    
    /**
     * 自定义失败处理器（可选）
     * 参数：(异常, 上下文)
     * 返回：Fallback 步骤
     */
    private BiFunction<Exception, PlanContext, Map<String, Object>> failureHandler;
    
    /**
     * 创建默认配置
     */
    public static ReActConfig defaults() {
        return ReActConfig.builder().build();
    }
    
    /**
     * 创建禁用记忆的配置
     */
    public static ReActConfig withoutMemory() {
        return ReActConfig.builder()
            .memoryEnabled(false)
            .build();
    }
}

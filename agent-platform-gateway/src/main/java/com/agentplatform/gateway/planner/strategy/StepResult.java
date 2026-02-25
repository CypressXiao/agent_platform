package com.agentplatform.gateway.planner.strategy;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 步骤执行结果
 */
@Data
@Builder
public class StepResult {
    
    /**
     * 步骤是否成功
     */
    private boolean success;
    
    /**
     * 执行输出
     */
    private Object output;
    
    /**
     * 错误信息（失败时）
     */
    private String error;
    
    /**
     * 执行耗时（毫秒）
     */
    private long latencyMs;
    
    /**
     * 额外元数据
     */
    private Map<String, Object> metadata;
    
    public static StepResult success(Object output, long latencyMs) {
        return StepResult.builder()
            .success(true)
            .output(output)
            .latencyMs(latencyMs)
            .build();
    }
    
    public static StepResult failed(String error, long latencyMs) {
        return StepResult.builder()
            .success(false)
            .error(error)
            .latencyMs(latencyMs)
            .build();
    }
}

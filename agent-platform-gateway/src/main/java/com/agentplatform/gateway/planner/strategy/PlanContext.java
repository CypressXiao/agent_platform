package com.agentplatform.gateway.planner.strategy;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划执行上下文，在步骤间传递状态
 */
@Data
@Builder
public class PlanContext {
    
    /**
     * 会话 ID（用于关联记忆）
     */
    private String sessionId;
    
    /**
     * 是否新会话（新会话无短期记忆）
     */
    @Builder.Default
    private boolean newSession = true;
    
    /**
     * 是否启用记忆
     * 默认关闭，开启后用户需要自己实现 queryMemories() 和 saveToMemory()
     */
    @Builder.Default
    private boolean memoryEnabled = false;
    
    /**
     * 原始目标
     */
    private String goal;
    
    /**
     * 全局状态（步骤间共享）
     */
    @Builder.Default
    private Map<String, Object> state = new HashMap<>();
    
    /**
     * 执行历史（每步的结果）
     */
    @Builder.Default
    private List<StepResult> history = new ArrayList<>();
    
    /**
     * 相关记忆（从 Memory 模块查询）
     */
    @Builder.Default
    private List<Map<String, Object>> memories = new ArrayList<>();
    
    /**
     * 可用工具列表
     */
    @Builder.Default
    private List<String> availableTools = new ArrayList<>();
    
    /**
     * 当前步骤索引
     */
    @Builder.Default
    private int currentStepIndex = 0;
    
    /**
     * 最大步骤数限制
     */
    @Builder.Default
    private int maxSteps = 10;
    
    /**
     * 是否需要人工审批
     */
    @Builder.Default
    private boolean approvalRequired = false;
    
    /**
     * 审批状态
     */
    private String approvalStatus;
    
    /**
     * 更新状态
     */
    public void updateState(String key, Object value) {
        this.state.put(key, value);
    }
    
    /**
     * 添加步骤结果到历史
     */
    public void addHistory(StepResult result) {
        this.history.add(result);
        this.currentStepIndex++;
    }
    
    /**
     * 获取上一步结果
     */
    public StepResult getLastResult() {
        if (history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }
    
    /**
     * 是否达到最大步骤数
     */
    public boolean isMaxStepsReached() {
        return currentStepIndex >= maxSteps;
    }
}

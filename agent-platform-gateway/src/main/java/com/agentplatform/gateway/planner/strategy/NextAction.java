package com.agentplatform.gateway.planner.strategy;

/**
 * 步骤执行后的下一步动作
 */
public enum NextAction {
    /**
     * 继续执行下一步
     */
    CONTINUE,
    
    /**
     * 重新规划（根据当前状态生成新的步骤）
     */
    REPLAN,
    
    /**
     * 等待人工审批
     */
    WAIT_APPROVAL,
    
    /**
     * 执行完成
     */
    END,
    
    /**
     * 执行失败，停止
     */
    FAILED
}

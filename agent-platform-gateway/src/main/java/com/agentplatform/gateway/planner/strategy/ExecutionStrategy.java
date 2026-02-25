package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.common.model.CallerIdentity;

import java.util.List;
import java.util.Map;

/**
 * 执行策略接口 - 定义不同的 Agent 执行范式
 * 
 * 内置实现：
 * - PlanThenExecuteStrategy: 先规划后执行
 * - ReActLoopStrategy: Reason-Act-Observe 循环
 * - HumanInLoopStrategy: 关键步骤需人工审批
 * 
 * SDK 用户可以实现此接口自定义策略
 */
public interface ExecutionStrategy {
    
    /**
     * 策略名称
     */
    String name();
    
    /**
     * 策略描述
     */
    default String description() {
        return name();
    }
    
    /**
     * 规划阶段：将目标分解为步骤
     * 
     * @param identity 调用方身份
     * @param goal 目标描述
     * @param context 执行上下文
     * @return 步骤列表
     */
    List<Map<String, Object>> plan(CallerIdentity identity, String goal, PlanContext context);
    
    /**
     * 执行单个步骤
     * 
     * @param identity 调用方身份
     * @param step 步骤定义
     * @param context 执行上下文
     * @return 步骤执行结果
     */
    StepResult executeStep(CallerIdentity identity, Map<String, Object> step, PlanContext context);
    
    /**
     * 步骤完成后的决策：决定下一步动作
     * 
     * @param step 当前步骤
     * @param result 执行结果
     * @param context 执行上下文
     * @return 下一步动作
     */
    NextAction onStepComplete(Map<String, Object> step, StepResult result, PlanContext context);
    
    /**
     * 步骤失败后的处理
     * 
     * @param step 失败的步骤
     * @param error 错误信息
     * @param context 执行上下文
     * @return 下一步动作（FAILED/REPLAN/CONTINUE）
     */
    default NextAction onStepFailed(Map<String, Object> step, Exception error, PlanContext context) {
        return NextAction.FAILED;
    }
    
    /**
     * 是否支持重新规划
     */
    default boolean supportsReplan() {
        return false;
    }
    
    /**
     * 是否支持人工审批
     */
    default boolean supportsHumanApproval() {
        return false;
    }
}

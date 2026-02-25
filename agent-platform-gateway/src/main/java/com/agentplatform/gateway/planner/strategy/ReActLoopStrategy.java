package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Loop 策略（抽象类）
 * 
 * 流程：Reason → Act → Observe → (循环直到完成)
 * 特点：每步执行后 LLM 观察结果，动态决定下一步
 * 
 * SDK 用户必须实现的抽象方法：
 * - buildReActSystemPrompt(): 构建 System Prompt
 * - buildReActUserPrompt(): 构建 User Prompt
 * 
 * SDK 用户可选覆盖的钩子方法：
 * - queryMemories(): 自定义记忆查询逻辑（需开启 memoryEnabled）
 * - saveToMemory(): 自定义记忆保存逻辑（需开启 memoryEnabled）
 * - getLlmModel(): 自定义 LLM 模型
 * - getLlmTemperature(): 自定义温度参数
 * - parseReActResponse(): 自定义响应解析
 * - onReasoningFailed(): 自定义失败处理
 */
@Slf4j
public abstract class ReActLoopStrategy extends BaseExecutionStrategy {

    public ReActLoopStrategy(ToolDispatcher toolDispatcher, ToolAggregator toolAggregator) {
        super(toolDispatcher, toolAggregator);
    }

    @Override
    public String name() {
        return "react";
    }

    @Override
    public String description() {
        return "ReAct 循环：每步执行后 LLM 观察结果，动态决定下一步";
    }

    // ==================== ExecutionStrategy 接口实现 ====================

    @Override
    public List<Map<String, Object>> plan(CallerIdentity identity, String goal, PlanContext context) {
        // 1. 只有开启记忆时才查询（用户需要自己实现 queryMemories）
        if (context.isMemoryEnabled()) {
            List<Map<String, Object>> memories = queryMemories(identity, goal, context);
            context.getMemories().addAll(memories);
        }
        
        // 2. ReAct 模式不预先生成完整计划，只生成第一步
        Map<String, Object> firstStep = reasonNextStep(identity, goal, context);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(firstStep);
        return steps;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult executeStep(CallerIdentity identity, Map<String, Object> step, PlanContext context) {
        String toolName = (String) step.get("tool");
        
        // 检查是否是结束标记
        if ("__finish__".equals(toolName)) {
            // 保存执行摘要到记忆（钩子方法，可覆盖）
            saveToMemory(identity, context.getGoal(), context, true);
            return StepResult.success(step.get("final_answer"), 0);
        }
        
        Map<String, Object> args = (Map<String, Object>) step.getOrDefault("arguments", Map.of());
        
        // 解析参数中的上下文引用
        Map<String, Object> resolvedArgs = resolveArguments(args, context);

        long start = System.currentTimeMillis();
        try {
            Object result = executeTool(identity, toolName, resolvedArgs);
            long latency = System.currentTimeMillis() - start;
            
            // 将结果存入上下文
            context.updateState("last_action", toolName);
            context.updateState("last_result", result);
            context.updateState("last_thought", step.get("thought"));
            
            return StepResult.success(result, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            context.updateState("last_action", toolName);
            context.updateState("last_error", e.getMessage());
            return onStepExecutionFailed(step, e, latency);
        }
    }

    @Override
    public NextAction onStepComplete(Map<String, Object> step, StepResult result, PlanContext context) {
        // 检查是否是结束步骤
        if ("__finish__".equals(step.get("tool"))) {
            return NextAction.END;
        }
        
        if (context.isMaxStepsReached()) {
            log.warn("ReAct loop reached max steps limit");
            return NextAction.END;
        }
        
        // 即使失败也继续，让 LLM 决定如何处理
        return NextAction.REPLAN;
    }

    @Override
    public NextAction onStepFailed(Map<String, Object> step, Exception error, PlanContext context) {
        // ReAct 模式下，失败后让 LLM 观察错误并决定下一步
        if (context.isMaxStepsReached()) {
            return NextAction.FAILED;
        }
        return NextAction.REPLAN;
    }

    @Override
    public boolean supportsReplan() {
        return true;
    }

    // ==================== 核心方法 ====================

    /**
     * Reason 阶段：LLM 根据当前状态决定下一步
     * 使用钩子方法构建 Prompt，支持自定义
     */
    public Map<String, Object> reasonNextStep(CallerIdentity identity, String goal, PlanContext context) {
        try {
            // 获取工具信息（包含描述）
            List<ToolInfo> tools = getToolInfos(identity);
            
            // 构建 Prompt（钩子方法，可覆盖）
            String systemPrompt = buildReActSystemPrompt(tools, context);
            String userPrompt = buildReActUserPrompt(goal, context);
            
            // 调用 LLM
            String llmOutput = callLlm(identity, systemPrompt, userPrompt, context);
            
            if (llmOutput != null) {
                return parseReActResponse(llmOutput);
            }
        } catch (Exception e) {
            log.warn("ReAct reasoning failed: {}", e.getMessage());
            return onReasoningFailed(e, context);
        }

        return onReasoningFailed(null, context);
    }

    // ==================== 抽象方法 - 子类必须实现 ====================

    /**
     * 构建 ReAct System Prompt
     * 
     * SDK 用户必须实现此方法，定义自己业务场景的 System Prompt。
     * 
     * @param tools 可用工具列表（包含名称、描述、输入 Schema）
     * @param context 执行上下文
     * @return System Prompt
     */
    protected abstract String buildReActSystemPrompt(List<ToolInfo> tools, PlanContext context);

    /**
     * 构建 ReAct User Prompt
     * 
     * SDK 用户必须实现此方法，定义自己业务场景的 User Prompt。
     * 可以利用 context 中的信息：
     * - context.getGoal(): 目标
     * - context.getMemories(): 相关记忆
     * - context.getHistory(): 执行历史
     * - context.getState(): 状态变量
     * 
     * @param goal 目标
     * @param context 执行上下文
     * @return User Prompt
     */
    protected abstract String buildReActUserPrompt(String goal, PlanContext context);

    /**
     * 解析 ReAct 响应
     * 子类可覆盖以自定义解析逻辑
     */
    protected Map<String, Object> parseReActResponse(String llmOutput) {
        return parseLlmResponse(llmOutput);
    }

    /**
     * 处理推理失败
     * 子类可覆盖以自定义失败处理
     */
    protected Map<String, Object> onReasoningFailed(Exception error, PlanContext context) {
        return Map.of(
            "thought", "Unable to reason" + (error != null ? ": " + error.getMessage() : ""),
            "tool", "__finish__",
            "final_answer", "Failed to complete the task"
        );
    }
}

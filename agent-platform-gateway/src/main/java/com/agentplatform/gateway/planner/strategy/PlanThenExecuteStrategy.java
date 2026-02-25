package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Plan-then-Execute 策略（抽象类）
 * 
 * 流程：LLM 一次性生成完整步骤 → 顺序执行 → 结束
 * 特点：简单，无反馈循环，失败即停止
 * 
 * SDK 用户必须实现的抽象方法：
 * - buildPlanningSystemPrompt(): 构建规划 System Prompt
 * - buildPlanningUserPrompt(): 构建规划 User Prompt
 * 
 * SDK 用户可选覆盖的钩子方法：
 * - queryMemories(): 自定义记忆查询逻辑（需开启 memoryEnabled）
 * - saveToMemory(): 自定义记忆保存逻辑（需开启 memoryEnabled）
 */
@Slf4j
public abstract class PlanThenExecuteStrategy extends BaseExecutionStrategy {

    public PlanThenExecuteStrategy(ToolDispatcher toolDispatcher, ToolAggregator toolAggregator) {
        super(toolDispatcher, toolAggregator);
    }

    @Override
    public String name() {
        return "plan_then_execute";
    }

    @Override
    public String description() {
        return "先规划后执行：LLM 一次性生成完整步骤，然后顺序执行";
    }

    @Override
    public List<Map<String, Object>> plan(CallerIdentity identity, String goal, PlanContext context) {
        // 获取工具信息
        List<ToolInfo> tools = getToolInfos(identity);
        
        // 构建 Prompt（抽象方法，子类实现）
        String systemPrompt = buildPlanningSystemPrompt(tools, context);
        String userPrompt = buildPlanningUserPrompt(goal, context);

        try {
            // 调用 LLM 生成步骤
            String llmOutput = callLlm(identity, systemPrompt, userPrompt, context);

            if (llmOutput != null) {
                return parseSteps(llmOutput, context.getAvailableTools());
            }
        } catch (Exception e) {
            log.warn("LLM planning failed, using fallback: {}", e.getMessage());
        }

        // Fallback：简单步骤
        return createFallbackSteps(goal);
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult executeStep(CallerIdentity identity, Map<String, Object> step, PlanContext context) {
        String toolName = (String) step.get("tool");
        Map<String, Object> args = (Map<String, Object>) step.getOrDefault("arguments", Map.of());

        // 解析参数中的上下文引用
        Map<String, Object> resolvedArgs = resolveArguments(args, context);

        long start = System.currentTimeMillis();
        try {
            Object result = toolDispatcher.dispatchInternal(identity, toolName, resolvedArgs);
            long latency = System.currentTimeMillis() - start;
            
            // 将结果存入上下文
            context.updateState("step_" + context.getCurrentStepIndex() + "_result", result);
            
            return StepResult.success(result, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return StepResult.failed(e.getMessage(), latency);
        }
    }

    @Override
    public NextAction onStepComplete(Map<String, Object> step, StepResult result, PlanContext context) {
        if (!result.isSuccess()) {
            return NextAction.FAILED;
        }
        
        if (context.isMaxStepsReached()) {
            return NextAction.END;
        }
        
        return NextAction.CONTINUE;
    }

    // ==================== 抽象方法 - 子类必须实现 ====================

    /**
     * 构建规划 System Prompt
     * 
     * SDK 用户必须实现此方法，定义自己业务场景的 System Prompt。
     * 
     * @param tools 可用工具列表
     * @param context 执行上下文
     * @return System Prompt
     */
    protected abstract String buildPlanningSystemPrompt(List<ToolInfo> tools, PlanContext context);

    /**
     * 构建规划 User Prompt
     * 
     * SDK 用户必须实现此方法，定义自己业务场景的 User Prompt。
     * 
     * @param goal 目标
     * @param context 执行上下文
     * @return User Prompt
     */
    protected abstract String buildPlanningUserPrompt(String goal, PlanContext context);

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String llmOutput, List<String> toolNames) {
        try {
            String json = llmOutput;
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            List<Map<String, Object>> steps = objectMapper.readValue(json, List.class);
            
            // 验证工具可访问性
            for (Map<String, Object> step : steps) {
                String toolName = (String) step.get("tool");
                if (toolName != null && !toolNames.contains(toolName)) {
                    throw new McpException(McpErrorCode.PLAN_TOOL_ACCESS_DENIED,
                        "Plan references inaccessible tool: " + toolName);
                }
            }
            
            return steps;
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse LLM plan output: {}", e.getMessage());
            return createFallbackSteps(llmOutput);
        }
    }

    private List<Map<String, Object>> createFallbackSteps(String goal) {
        return List.of(Map.of(
            "description", "Execute goal: " + goal,
            "tool", "echo",
            "arguments", Map.of("message", goal),
            "status", "PENDING"
        ));
    }

}

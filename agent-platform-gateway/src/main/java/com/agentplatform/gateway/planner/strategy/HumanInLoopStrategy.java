package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-in-the-Loop 策略（抽象类）
 * 
 * 流程：执行步骤 → 检查是否需要审批 → 暂停等待 → 继续执行
 * 特点：关键步骤需要人工确认后才能继续
 * 
 * SDK 用户必须实现的抽象方法：
 * - buildPlanningSystemPrompt(): 构建规划 System Prompt
 * - buildPlanningUserPrompt(): 构建规划 User Prompt
 * - getApprovalRequiredTools(): 返回需要审批的工具列表
 * 
 * SDK 用户可选覆盖的钩子方法：
 * - queryMemories(): 自定义记忆查询逻辑（需开启 memoryEnabled）
 * - saveToMemory(): 自定义记忆保存逻辑（需开启 memoryEnabled）
 */
@Slf4j
public abstract class HumanInLoopStrategy extends BaseExecutionStrategy {

    public HumanInLoopStrategy(ToolDispatcher toolDispatcher, ToolAggregator toolAggregator) {
        super(toolDispatcher, toolAggregator);
    }

    @Override
    public String name() {
        return "human_in_loop";
    }

    @Override
    public String description() {
        return "Human-in-the-Loop：关键步骤暂停等待人工审批";
    }

    @Override
    public List<Map<String, Object>> plan(CallerIdentity identity, String goal, PlanContext context) {
        // 获取工具信息
        List<ToolInfo> tools = getToolInfos(identity);
        
        // 构建 Prompt（抽象方法，子类实现）
        String systemPrompt = buildPlanningSystemPrompt(tools, context);
        String userPrompt = buildPlanningUserPrompt(goal, context);

        try {
            String llmOutput = callLlm(identity, systemPrompt, userPrompt, context);

            if (llmOutput != null) {
                List<Map<String, Object>> steps = parseSteps(llmOutput);
                
                // 标记需要审批的步骤
                for (Map<String, Object> step : steps) {
                    String toolName = (String) step.get("tool");
                    if (requiresApproval(toolName)) {
                        step.put("requires_approval", true);
                    }
                }
                
                return steps;
            }
        } catch (Exception e) {
            log.warn("LLM planning failed: {}", e.getMessage());
        }

        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult executeStep(CallerIdentity identity, Map<String, Object> step, PlanContext context) {
        String toolName = (String) step.get("tool");
        
        // 检查是否需要审批且未审批
        if (requiresApproval(toolName) && !isApproved(step, context)) {
            context.setApprovalRequired(true);
            context.setApprovalStatus("PENDING");
            return StepResult.builder()
                .success(false)
                .error("Approval required for tool: " + toolName)
                .metadata(Map.of(
                    "approval_required", true,
                    "tool", toolName,
                    "step", step
                ))
                .build();
        }

        Map<String, Object> args = (Map<String, Object>) step.getOrDefault("arguments", Map.of());

        long start = System.currentTimeMillis();
        try {
            Object result = toolDispatcher.dispatchInternal(identity, toolName, args);
            long latency = System.currentTimeMillis() - start;
            
            context.updateState("step_" + context.getCurrentStepIndex() + "_result", result);
            
            return StepResult.success(result, latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return StepResult.failed(e.getMessage(), latency);
        }
    }

    @Override
    public NextAction onStepComplete(Map<String, Object> step, StepResult result, PlanContext context) {
        // 检查是否需要等待审批
        if (result.getMetadata() != null && 
            Boolean.TRUE.equals(result.getMetadata().get("approval_required"))) {
            return NextAction.WAIT_APPROVAL;
        }
        
        if (!result.isSuccess()) {
            return NextAction.FAILED;
        }
        
        if (context.isMaxStepsReached()) {
            return NextAction.END;
        }
        
        return NextAction.CONTINUE;
    }

    @Override
    public boolean supportsHumanApproval() {
        return true;
    }

    // ==================== 抽象方法 - 子类必须实现 ====================

    /**
     * 构建规划 System Prompt
     */
    protected abstract String buildPlanningSystemPrompt(List<ToolInfo> tools, PlanContext context);

    /**
     * 构建规划 User Prompt
     */
    protected abstract String buildPlanningUserPrompt(String goal, PlanContext context);

    /**
     * 返回需要审批的工具列表
     * SDK 用户必须实现此方法，定义哪些工具需要人工审批
     */
    protected abstract Set<String> getApprovalRequiredTools();

    /**
     * 检查工具是否需要审批
     */
    public boolean requiresApproval(String toolName) {
        return getApprovalRequiredTools().contains(toolName);
    }

    /**
     * 检查步骤是否已审批
     */
    private boolean isApproved(Map<String, Object> step, PlanContext context) {
        return "APPROVED".equals(context.getApprovalStatus()) ||
               Boolean.TRUE.equals(step.get("approved"));
    }


    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String llmOutput) {
        try {
            String json = llmOutput;
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse steps: {}", e.getMessage());
            return List.of();
        }
    }
}

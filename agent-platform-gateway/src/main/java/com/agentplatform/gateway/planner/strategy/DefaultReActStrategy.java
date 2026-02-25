package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 默认 ReAct 策略实现（Demo）
 * 
 * 这是一个开箱即用的 ReAct 实现，提供通用的 Prompt 模板。
 * 
 * 生产环境建议：
 * 1. 继承 ReActLoopStrategy 实现自己的策略
 * 2. 根据业务场景定制 System Prompt 和 User Prompt
 * 3. 实现 queryMemories() 和 saveToMemory() 以支持记忆功能
 * 
 * 示例：
 * <pre>
 * public class MyReActStrategy extends ReActLoopStrategy {
 *     
 *     public MyReActStrategy(ToolDispatcher toolDispatcher, ToolAggregator toolAggregator) {
 *         super(toolDispatcher, toolAggregator);
 *     }
 *     
 *     @Override
 *     protected String buildReActSystemPrompt(List<ToolInfo> tools, PlanContext context) {
 *         return "你是一个专业的客服助手...";
 *     }
 *     
 *     @Override
 *     protected String buildReActUserPrompt(String goal, PlanContext context) {
 *         return "用户问题: " + goal + "...";
 *     }
 * }
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@Slf4j
public class DefaultReActStrategy extends ReActLoopStrategy {

    private final StrategyRegistry strategyRegistry;

    public DefaultReActStrategy(ToolDispatcher toolDispatcher,
                                 ToolAggregator toolAggregator,
                                 StrategyRegistry strategyRegistry) {
        super(toolDispatcher, toolAggregator);
        this.strategyRegistry = strategyRegistry;
    }

    @PostConstruct
    public void register() {
        strategyRegistry.register(this);
    }

    @Override
    public String name() {
        return "react";
    }

    @Override
    public String description() {
        return "ReAct 循环（默认实现）：每步执行后 LLM 观察结果，动态决定下一步";
    }

    // ==================== Prompt 实现 ====================

    @Override
    protected String buildReActSystemPrompt(List<ToolInfo> tools, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI assistant that uses tools to accomplish tasks.\n\n");
        
        sb.append("Available tools:\n");
        for (ToolInfo tool : tools) {
            sb.append("- ").append(tool.name());
            if (tool.description() != null && !tool.description().isEmpty()) {
                sb.append(": ").append(tool.description());
            }
            sb.append("\n");
        }
        sb.append("\n");
        
        sb.append("For each step, respond with a JSON object containing:\n");
        sb.append("- \"thought\": Your reasoning about what to do next\n");
        sb.append("- \"tool\": The tool to use (or \"__finish__\" if the task is complete)\n");
        sb.append("- \"arguments\": The arguments for the tool (if applicable)\n");
        sb.append("- \"final_answer\": Your final answer (only if tool is \"__finish__\")\n\n");
        sb.append("Always respond with valid JSON only.");
        
        return sb.toString();
    }

    @Override
    protected String buildReActUserPrompt(String goal, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append("\n\n");

        // 添加记忆上下文
        if (!context.getMemories().isEmpty()) {
            sb.append("Relevant memories:\n");
            for (Map<String, Object> memory : context.getMemories()) {
                sb.append("- ").append(memory.getOrDefault("content", memory)).append("\n");
            }
            sb.append("\n");
        }

        // 添加 Scratchpad（思考历史）
        if (!context.getHistory().isEmpty()) {
            sb.append("Scratchpad:\n");
            for (int i = 0; i < context.getHistory().size(); i++) {
                StepResult result = context.getHistory().get(i);
                Object thought = context.getState().get("thought_" + i);
                if (thought != null) {
                    sb.append("Thought ").append(i + 1).append(": ").append(thought).append("\n");
                }
                Object action = context.getState().get("action_" + i);
                if (action != null) {
                    sb.append("Action ").append(i + 1).append(": ").append(action).append("\n");
                }
                sb.append("Observation ").append(i + 1).append(": ");
                if (result.isSuccess()) {
                    sb.append(result.getOutput());
                } else {
                    sb.append("ERROR - ").append(result.getError());
                }
                sb.append("\n\n");
            }
        }

        // 添加当前状态
        Object lastResult = context.getState().get("last_result");
        Object lastError = context.getState().get("last_error");
        if (lastResult != null && context.getHistory().isEmpty()) {
            sb.append("Last observation: ").append(lastResult).append("\n\n");
        }
        if (lastError != null && context.getHistory().isEmpty()) {
            sb.append("Last error: ").append(lastError).append("\n\n");
        }

        sb.append("What should be the next step? Respond with JSON.");
        return sb.toString();
    }
}

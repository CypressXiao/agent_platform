package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认 Plan-then-Execute 策略实现（Demo）
 * 
 * 这是一个开箱即用的实现，提供通用的 Prompt 模板。
 * 
 * 生产环境建议：
 * 1. 继承 PlanThenExecuteStrategy 实现自己的策略
 * 2. 根据业务场景定制 System Prompt 和 User Prompt
 */
@Component
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@Slf4j
public class DefaultPlanThenExecuteStrategy extends PlanThenExecuteStrategy {

    private final StrategyRegistry strategyRegistry;

    public DefaultPlanThenExecuteStrategy(ToolDispatcher toolDispatcher,
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
        return "plan_then_execute";
    }

    @Override
    public String description() {
        return "先规划后执行（默认实现）：LLM 一次性生成完整步骤，然后顺序执行";
    }

    // ==================== Prompt 实现 ====================

    @Override
    protected String buildPlanningSystemPrompt(List<ToolInfo> tools, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a task planner. Decompose the goal into steps using available tools.\n\n");
        
        sb.append("Available tools:\n");
        for (ToolInfo tool : tools) {
            sb.append("- ").append(tool.name());
            if (tool.description() != null && !tool.description().isEmpty()) {
                sb.append(": ").append(tool.description());
            }
            sb.append("\n");
        }
        sb.append("\n");
        
        sb.append("Return a JSON array of steps. Each step should have:\n");
        sb.append("- \"description\" (string): What this step does\n");
        sb.append("- \"tool\" (string): The tool to use\n");
        sb.append("- \"arguments\" (object): The arguments for the tool\n\n");
        sb.append("Always respond with valid JSON array only.");
        
        return sb.toString();
    }

    @Override
    protected String buildPlanningUserPrompt(String goal, PlanContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append("\n\n");
        
        if (!context.getState().isEmpty()) {
            sb.append("Current context: ").append(context.getState()).append("\n\n");
        }
        
        sb.append("Create a step-by-step plan as a JSON array.");
        return sb.toString();
    }
}

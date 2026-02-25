package com.agentplatform.gateway.planner.strategy;

import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 默认 Human-in-the-Loop 策略实现（Demo）
 * 
 * 这是一个开箱即用的实现，提供通用的 Prompt 模板和默认的审批工具列表。
 * 
 * 生产环境建议：
 * 1. 继承 HumanInLoopStrategy 实现自己的策略
 * 2. 根据业务场景定制 System Prompt 和 User Prompt
 * 3. 定义自己的需要审批的工具列表
 */
@Component
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@Slf4j
public class DefaultHumanInLoopStrategy extends HumanInLoopStrategy {

    private final StrategyRegistry strategyRegistry;

    /**
     * 默认需要审批的工具列表
     */
    private static final Set<String> DEFAULT_APPROVAL_REQUIRED_TOOLS = Set.of(
        "send_email",
        "delete_file",
        "execute_payment",
        "modify_database"
    );

    public DefaultHumanInLoopStrategy(ToolDispatcher toolDispatcher,
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
        return "human_in_loop";
    }

    @Override
    public String description() {
        return "Human-in-the-Loop（默认实现）：关键步骤暂停等待人工审批";
    }

    // ==================== 抽象方法实现 ====================

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
        
        sb.append("IMPORTANT: Some tools require human approval before execution:\n");
        for (String tool : getApprovalRequiredTools()) {
            sb.append("- ").append(tool).append(" (requires approval)\n");
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

    @Override
    protected Set<String> getApprovalRequiredTools() {
        return DEFAULT_APPROVAL_REQUIRED_TOOLS;
    }
}

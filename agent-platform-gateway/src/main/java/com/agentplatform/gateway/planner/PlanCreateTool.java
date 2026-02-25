package com.agentplatform.gateway.planner;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.planner.model.Plan;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlanCreateTool implements BuiltinToolHandler {

    private final PlanningEngine planningEngine;

    @Override
    public String toolName() {
        return "plan_create";
    }

    @Override
    public String description() {
        return "Create a step-by-step execution plan for a given goal using LLM-driven decomposition.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "goal", Map.of("type", "string", "description", "The goal to plan for"),
                "context", Map.of("type", "object", "description", "Additional context for planning")
            ),
            "required", List.of("goal")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String goal = (String) arguments.get("goal");
        Map<String, Object> context = (Map<String, Object>) arguments.getOrDefault("context", Map.of());

        Plan plan = planningEngine.createPlan(identity, goal, context);

        return Map.of(
            "plan_id", plan.getPlanId(),
            "goal", plan.getGoal(),
            "steps", plan.getSteps(),
            "status", plan.getStatus(),
            "llm_model", plan.getLlmModel() != null ? plan.getLlmModel() : "",
            "llm_tokens_used", plan.getLlmTokensUsed() != null ? plan.getLlmTokensUsed() : 0
        );
    }
}

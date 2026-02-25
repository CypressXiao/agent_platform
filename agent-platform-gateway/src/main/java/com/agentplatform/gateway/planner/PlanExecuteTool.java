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
public class PlanExecuteTool implements BuiltinToolHandler {

    private final PlanningEngine planningEngine;

    @Override
    public String toolName() {
        return "plan_execute";
    }

    @Override
    public String description() {
        return "Execute a previously created plan step by step.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "plan_id", Map.of("type", "string", "description", "The plan ID to execute")
            ),
            "required", List.of("plan_id")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String planId = (String) arguments.get("plan_id");
        Plan plan = planningEngine.executePlan(identity, planId);

        return Map.of(
            "plan_id", plan.getPlanId(),
            "status", plan.getStatus(),
            "steps", plan.getSteps()
        );
    }
}

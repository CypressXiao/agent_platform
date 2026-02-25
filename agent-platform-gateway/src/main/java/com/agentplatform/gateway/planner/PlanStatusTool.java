package com.agentplatform.gateway.planner;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.planner.model.Plan;
import com.agentplatform.gateway.planner.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlanStatusTool implements BuiltinToolHandler {

    private final PlanRepository planRepo;

    @Override
    public String toolName() {
        return "plan_status";
    }

    @Override
    public String description() {
        return "Query the status and details of a plan by its ID.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "plan_id", Map.of("type", "string", "description", "The plan ID to query")
            ),
            "required", List.of("plan_id")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String planId = (String) arguments.get("plan_id");
        Plan plan = planRepo.findById(planId)
            .orElseThrow(() -> new McpException(McpErrorCode.PLAN_NOT_FOUND, "Plan not found: " + planId));

        return Map.of(
            "plan_id", plan.getPlanId(),
            "goal", plan.getGoal(),
            "status", plan.getStatus(),
            "steps", plan.getSteps() != null ? plan.getSteps() : List.of(),
            "created_at", plan.getCreatedAt().toString(),
            "completed_at", plan.getCompletedAt() != null ? plan.getCompletedAt().toString() : ""
        );
    }
}

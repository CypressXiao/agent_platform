package com.agentplatform.gateway.planner;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.agentplatform.gateway.planner.model.Plan;
import com.agentplatform.gateway.planner.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Planning engine that uses LLM to decompose goals into executable steps.
 * Each step references a tool that the caller has access to.
 */
@Service
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PlanningEngine {

    private final PlanRepository planRepo;
    private final ToolAggregator toolAggregator;
    private final ToolDispatcher toolDispatcher;

    /**
     * Create a plan by calling LLM to decompose the goal into steps.
     */
    @SuppressWarnings("unchecked")
    public Plan createPlan(CallerIdentity identity, String goal, Map<String, Object> context) {
        // Get available tools for the caller
        List<ToolAggregator.ToolView> availableTools = toolAggregator.listTools(identity);
        List<String> toolNames = availableTools.stream().map(ToolAggregator.ToolView::name).toList();

        // Build prompt for LLM
        String prompt = buildPlanningPrompt(goal, toolNames, context);

        // Call LLM via the llm_chat built-in tool (if available)
        List<Map<String, Object>> steps;
        String llmModel = "default";
        int tokensUsed = 0;

        try {
            Map<String, Object> llmArgs = Map.of(
                "model", "default",
                "messages", List.of(
                    Map.of("role", "system", "content", "You are a task planner. Decompose the goal into steps using available tools. Return JSON array of steps."),
                    Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.3
            );

            Object llmResult = toolDispatcher.dispatchInternal(identity, "llm_chat", llmArgs);

            if (llmResult instanceof Map<?, ?> resultMap) {
                String content = (String) resultMap.get("content");
                llmModel = resultMap.containsKey("model") ? String.valueOf(resultMap.get("model")) : "default";
                Map<String, Object> usage = (Map<String, Object>) resultMap.get("usage");
                if (usage != null) {
                    tokensUsed = ((Number) usage.getOrDefault("total_tokens", 0)).intValue();
                }
                steps = parseSteps(content, toolNames);
            } else {
                steps = createFallbackSteps(goal, toolNames);
            }
        } catch (Exception e) {
            log.warn("LLM planning failed, using fallback: {}", e.getMessage());
            steps = createFallbackSteps(goal, toolNames);
        }

        // Validate that all referenced tools are accessible
        for (Map<String, Object> step : steps) {
            String toolName = (String) step.get("tool");
            if (toolName != null && !toolNames.contains(toolName)) {
                throw new McpException(McpErrorCode.PLAN_TOOL_ACCESS_DENIED,
                    "Plan references inaccessible tool: " + toolName);
            }
        }

        Plan plan = Plan.builder()
            .planId(UUID.randomUUID().toString())
            .actorTid(identity.tenantId())
            .goal(goal)
            .steps(steps)
            .context(context)
            .status("CREATED")
            .llmModel(llmModel)
            .llmTokensUsed(tokensUsed)
            .build();

        return planRepo.save(plan);
    }

    /**
     * Execute a plan step by step.
     */
    @SuppressWarnings("unchecked")
    public Plan executePlan(CallerIdentity identity, String planId) {
        Plan plan = planRepo.findById(planId)
            .orElseThrow(() -> new McpException(McpErrorCode.PLAN_NOT_FOUND, "Plan not found: " + planId));

        if (!identity.tenantId().equals(plan.getActorTid())) {
            throw new McpException(McpErrorCode.FORBIDDEN_POLICY, "Not authorized to execute this plan");
        }

        plan.setStatus("EXECUTING");
        planRepo.save(plan);

        List<Map<String, Object>> steps = plan.getSteps();
        Map<String, Object> stepContext = new HashMap<>(plan.getContext() != null ? plan.getContext() : Map.of());

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = new HashMap<>(steps.get(i));
            String toolName = (String) step.get("tool");
            Map<String, Object> args = (Map<String, Object>) step.getOrDefault("arguments", Map.of());

            // Resolve arguments from context
            Map<String, Object> resolvedArgs = resolveStepArguments(args, stepContext);

            try {
                Object result = toolDispatcher.dispatchInternal(identity, toolName, resolvedArgs);
                step.put("status", "COMPLETED");
                step.put("result", result);
                stepContext.put("step_" + i + "_result", result);
            } catch (Exception e) {
                step.put("status", "FAILED");
                step.put("error", e.getMessage());
                steps.set(i, step);
                plan.setSteps(steps);
                plan.setStatus("FAILED");
                plan.setCompletedAt(Instant.now());
                return planRepo.save(plan);
            }

            steps.set(i, step);
        }

        plan.setSteps(steps);
        plan.setStatus("COMPLETED");
        plan.setCompletedAt(Instant.now());
        return planRepo.save(plan);
    }

    private String buildPlanningPrompt(String goal, List<String> toolNames, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append("\n\n");
        sb.append("Available tools: ").append(String.join(", ", toolNames)).append("\n\n");
        if (context != null && !context.isEmpty()) {
            sb.append("Context: ").append(context).append("\n\n");
        }
        sb.append("Create a step-by-step plan as a JSON array. Each step should have: ");
        sb.append("\"description\" (string), \"tool\" (string, one of the available tools), ");
        sb.append("\"arguments\" (object, tool input arguments).");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String llmOutput, List<String> toolNames) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Try to extract JSON array from LLM output
            String json = llmOutput;
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse LLM plan output, using fallback");
            return createFallbackSteps(llmOutput, toolNames);
        }
    }

    private List<Map<String, Object>> createFallbackSteps(String goal, List<String> toolNames) {
        // Simple fallback: create a single step using echo tool
        return List.of(Map.of(
            "description", "Execute goal: " + goal,
            "tool", "echo",
            "arguments", Map.of("message", goal),
            "status", "PENDING"
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveStepArguments(Map<String, Object> args, Map<String, Object> context) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String ref && ref.startsWith("$context.")) {
                String key = ref.substring("$context.".length());
                resolved.put(entry.getKey(), context.getOrDefault(key, ref));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }
}

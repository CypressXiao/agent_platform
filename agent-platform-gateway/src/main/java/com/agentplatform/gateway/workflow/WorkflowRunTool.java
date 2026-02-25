package com.agentplatform.gateway.workflow;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.workflow.engine.DagExecutionEngine;
import com.agentplatform.gateway.workflow.model.WorkflowGraph;
import com.agentplatform.gateway.workflow.model.WorkflowRun;
import com.agentplatform.gateway.workflow.repository.WorkflowGraphRepository;
import com.agentplatform.gateway.workflow.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent-platform.workflow.enabled", havingValue = "true")
@RequiredArgsConstructor
public class WorkflowRunTool implements BuiltinToolHandler {

    private final WorkflowGraphRepository graphRepo;
    private final WorkflowRunRepository runRepo;
    private final DagExecutionEngine dagEngine;

    @Override
    public String toolName() {
        return "workflow_run";
    }

    @Override
    public String description() {
        return "Execute a published workflow graph by ID. Returns the workflow run result.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "graph_id", Map.of("type", "string", "description", "Workflow graph template ID"),
                "input", Map.of("type", "object", "description", "Input parameters for the workflow")
            ),
            "required", List.of("graph_id")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String graphId = (String) arguments.get("graph_id");
        Map<String, Object> input = (Map<String, Object>) arguments.getOrDefault("input", Map.of());

        WorkflowGraph graph = graphRepo.findById(graphId)
            .orElseThrow(() -> new McpException(McpErrorCode.GRAPH_NOT_FOUND,
                "Graph not found: " + graphId));

        if (!"published".equals(graph.getStatus())) {
            throw new McpException(McpErrorCode.GRAPH_NOT_PUBLISHED,
                "Graph is not published: " + graphId);
        }

        // Permission check: caller must be owner or have grant
        if (!identity.getTenantId().equals(graph.getOwnerTid())) {
            throw new McpException(McpErrorCode.FORBIDDEN_POLICY,
                "Not authorized to run workflow owned by " + graph.getOwnerTid());
        }

        WorkflowRun run = dagEngine.execute(identity, graph, input);

        return Map.of(
            "run_id", run.getRunId(),
            "status", run.getStatus(),
            "output", run.getOutput() != null ? run.getOutput() : Map.of(),
            "total_latency_ms", run.getTotalLatencyMs() != null ? run.getTotalLatencyMs() : 0
        );
    }
}

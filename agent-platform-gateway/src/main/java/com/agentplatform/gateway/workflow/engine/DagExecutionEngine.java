package com.agentplatform.gateway.workflow.engine;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolDispatcher;
import com.agentplatform.gateway.workflow.model.WorkflowGraph;
import com.agentplatform.gateway.workflow.model.WorkflowRun;
import com.agentplatform.gateway.workflow.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAG-based workflow execution engine.
 * Performs topological sorting and executes nodes in parallel where possible.
 */
@Service
@ConditionalOnProperty(name = "agent-platform.workflow.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DagExecutionEngine {

    private final ToolDispatcher toolDispatcher;
    private final WorkflowRunRepository runRepo;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @SuppressWarnings("unchecked")
    public WorkflowRun execute(CallerIdentity identity, WorkflowGraph graph, Map<String, Object> input) {
        Map<String, Object> definition = graph.getDefinition();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) definition.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) definition.getOrDefault("edges", List.of());

        if (nodes == null || nodes.isEmpty()) {
            throw new McpException(McpErrorCode.BAD_REQUEST, "Workflow graph has no nodes");
        }

        // Create run record
        WorkflowRun run = WorkflowRun.builder()
            .runId(UUID.randomUUID().toString())
            .graphId(graph.getGraphId())
            .graphVersion(graph.getVersion())
            .actorTid(identity.getTenantId())
            .input(input)
            .status("RUNNING")
            .startedAt(Instant.now())
            .build();
        run = runRepo.save(run);

        try {
            // Build adjacency list and in-degree map
            Map<String, List<String>> adjacency = new HashMap<>();
            Map<String, Integer> inDegree = new HashMap<>();
            Map<String, Map<String, Object>> nodeMap = new HashMap<>();

            for (Map<String, Object> node : nodes) {
                String nodeId = (String) node.get("id");
                nodeMap.put(nodeId, node);
                adjacency.put(nodeId, new ArrayList<>());
                inDegree.put(nodeId, 0);
            }

            for (Map<String, Object> edge : edges) {
                String from = (String) edge.get("from");
                String to = (String) edge.get("to");
                adjacency.get(from).add(to);
                inDegree.merge(to, 1, Integer::sum);
            }

            // Topological sort + parallel execution
            Map<String, Object> nodeResults = new ConcurrentHashMap<>();
            Map<String, Object> nodeExecutions = new ConcurrentHashMap<>();
            Map<String, Object> context = new HashMap<>(input);

            // BFS-based topological execution
            Queue<String> ready = new LinkedList<>();
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0) {
                    ready.add(entry.getKey());
                }
            }

            while (!ready.isEmpty()) {
                // Execute all ready nodes in parallel
                List<String> batch = new ArrayList<>(ready);
                ready.clear();

                List<CompletableFuture<Void>> futures = batch.stream()
                    .map(nodeId -> CompletableFuture.runAsync(() -> {
                        Map<String, Object> node = nodeMap.get(nodeId);
                        long nodeStart = System.currentTimeMillis();
                        try {
                            Object result = executeNode(identity, node, context, nodeResults);
                            nodeResults.put(nodeId, result);
                            nodeExecutions.put(nodeId, Map.of(
                                "status", "COMPLETED",
                                "result", result != null ? result : Map.of(),
                                "latency_ms", System.currentTimeMillis() - nodeStart
                            ));
                        } catch (Exception e) {
                            nodeExecutions.put(nodeId, Map.of(
                                "status", "FAILED",
                                "error", e.getMessage(),
                                "latency_ms", System.currentTimeMillis() - nodeStart
                            ));
                            throw new McpException(McpErrorCode.WORKFLOW_NODE_FAILED,
                                "Node %s failed: %s".formatted(nodeId, e.getMessage()), e);
                        }
                    }, executor))
                    .toList();

                // Wait for all parallel nodes to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Find next ready nodes
                for (String completedNode : batch) {
                    for (String next : adjacency.get(completedNode)) {
                        int remaining = inDegree.merge(next, -1, Integer::sum);
                        if (remaining == 0) {
                            ready.add(next);
                        }
                    }
                }
            }

            // Apply output mapping
            Map<String, Object> output = applyOutputMapping(definition, nodeResults);

            run.setStatus("COMPLETED");
            run.setOutput(output);
            run.setNodeExecutions(nodeExecutions);
            run.setCompletedAt(Instant.now());
            run.setTotalLatencyMs(System.currentTimeMillis() - run.getStartedAt().toEpochMilli());

        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setCompletedAt(Instant.now());
            run.setTotalLatencyMs(System.currentTimeMillis() - run.getStartedAt().toEpochMilli());
            log.error("Workflow execution failed: run={}, error={}", run.getRunId(), e.getMessage(), e);
        }

        return runRepo.save(run);
    }

    @SuppressWarnings("unchecked")
    private Object executeNode(CallerIdentity identity, Map<String, Object> node,
                                Map<String, Object> context, Map<String, Object> nodeResults) {
        String type = (String) node.getOrDefault("type", "tool_call");
        String toolName = (String) node.get("tool_name");
        Map<String, Object> inputMapping = (Map<String, Object>) node.getOrDefault("input_mapping", Map.of());

        // Resolve input arguments from context and previous node results
        Map<String, Object> arguments = resolveArguments(inputMapping, context, nodeResults);

        if ("tool_call".equals(type)) {
            return toolDispatcher.dispatchInternal(identity, toolName, arguments);
        } else if ("condition".equals(type)) {
            // Simple condition evaluation
            String condition = (String) node.get("condition");
            return Map.of("result", evaluateCondition(condition, context, nodeResults));
        }

        throw new McpException(McpErrorCode.BAD_REQUEST, "Unknown node type: " + type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArguments(Map<String, Object> inputMapping,
                                                  Map<String, Object> context,
                                                  Map<String, Object> nodeResults) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : inputMapping.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String ref) {
                if (ref.startsWith("$input.")) {
                    resolved.put(key, context.get(ref.substring("$input.".length())));
                } else if (ref.startsWith("$node.")) {
                    // $node.nodeId.field
                    String[] parts = ref.substring("$node.".length()).split("\\.", 2);
                    Object nodeResult = nodeResults.get(parts[0]);
                    if (parts.length > 1 && nodeResult instanceof Map) {
                        resolved.put(key, ((Map<String, Object>) nodeResult).get(parts[1]));
                    } else {
                        resolved.put(key, nodeResult);
                    }
                } else {
                    resolved.put(key, value);
                }
            } else {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    private boolean evaluateCondition(String condition, Map<String, Object> context,
                                       Map<String, Object> nodeResults) {
        // Simple condition: check if a value is truthy
        if (condition != null && condition.startsWith("$node.")) {
            String[] parts = condition.substring("$node.".length()).split("\\.", 2);
            Object result = nodeResults.get(parts[0]);
            return result != null && !Boolean.FALSE.equals(result);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyOutputMapping(Map<String, Object> definition,
                                                     Map<String, Object> nodeResults) {
        Map<String, Object> outputMapping = (Map<String, Object>) definition.get("outputMapping");
        if (outputMapping == null) {
            return new HashMap<>(nodeResults);
        }

        Map<String, Object> output = new HashMap<>();
        for (Map.Entry<String, Object> entry : outputMapping.entrySet()) {
            String key = entry.getKey();
            Object ref = entry.getValue();
            if (ref instanceof String refStr && refStr.startsWith("$node.")) {
                String[] parts = refStr.substring("$node.".length()).split("\\.", 2);
                Object nodeResult = nodeResults.get(parts[0]);
                if (parts.length > 1 && nodeResult instanceof Map) {
                    output.put(key, ((Map<String, Object>) nodeResult).get(parts[1]));
                } else {
                    output.put(key, nodeResult);
                }
            } else {
                output.put(key, ref);
            }
        }
        return output;
    }
}

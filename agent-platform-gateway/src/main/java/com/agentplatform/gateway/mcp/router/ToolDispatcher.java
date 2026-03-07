package com.agentplatform.gateway.mcp.router;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Grant;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.common.repository.ToolRepository;
import com.agentplatform.common.repository.UpstreamServerRepository;
import com.agentplatform.gateway.audit.AuditLogService;
import com.agentplatform.gateway.authz.GrantEngine;
import com.agentplatform.gateway.authz.PolicyEngine;
import com.agentplatform.gateway.authz.ScopeValidator;
import com.agentplatform.gateway.governance.GovernanceInterceptor;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.mcp.registry.BuiltinToolRegistry;
import com.agentplatform.gateway.mcp.upstream.McpProxyService;
import com.agentplatform.gateway.mcp.upstream.RestProxyService;
import com.agentplatform.gateway.validation.JsonSchemaValidator;
import com.agentplatform.gateway.job.AsyncToolAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Core routing engine for tools/call.
 * Performs permission checks, governance, and dispatches to the appropriate handler.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolDispatcher {

    private final ToolRepository toolRepo;
    private final UpstreamServerRepository serverRepo;
    private final BuiltinToolRegistry builtinRegistry;
    private final ScopeValidator scopeValidator;
    private final PolicyEngine policyEngine;
    private final GrantEngine grantEngine;
    private final GovernanceInterceptor governance;
    private final McpProxyService mcpProxy;
    private final RestProxyService restProxy;
    private final AuditLogService auditLog;
    private final JsonSchemaValidator schemaValidator;
    private final AsyncToolAdapter asyncToolAdapter;

    /**
     * Dispatch a tool call from an external caller (Agent).
     */
    public Object dispatch(CallerIdentity identity, String toolName, Map<String, Object> arguments) {
        return dispatch(identity, toolName, arguments, null, null, null);
    }

    /**
     * Dispatch with context (runId, stepId, conversationId) for async support
     */
    public Object dispatch(CallerIdentity identity, String toolName, Map<String, Object> arguments,
                           String runId, String stepId, String conversationId) {
        long start = System.currentTimeMillis();
        String grantId = null;

        try {
            // 1. Resolve tool
            Tool tool = resolveToolForCaller(identity, toolName);

            // 2. JSON Schema validation
            schemaValidator.validate(toolName, arguments, tool.getInputSchema());

            // 3. Scope validation
            scopeValidator.validate(identity, tool);

            // 4. Policy check
            if (!policyEngine.evaluate(identity, tool)) {
                throw new McpException(McpErrorCode.FORBIDDEN_POLICY,
                    "Policy denied access to tool: " + toolName);
            }

            // 5. Cross-tenant Grant check
            if (!identity.getTenantId().equals(tool.getOwnerTid()) && !"system".equals(tool.getOwnerTid())) {
                Grant grant = grantEngine.check(identity.getTenantId(), tool.getOwnerTid(), tool.getToolId());
                grantId = grant != null ? grant.getGrantId() : null;
            }

            // 6. Governance pre-check (rate limit, circuit breaker)
            governance.preCheck(identity, tool);

            // 7. Route to handler (async or sync)
            Object result = routeToHandler(identity, tool, arguments, runId, stepId, conversationId);

            // 8. Governance post-check
            governance.postCheck(identity, tool, true);

            // 9. Audit
            long latency = System.currentTimeMillis() - start;
            auditLog.logSuccess(identity, tool, grantId, latency, arguments, result);

            return result;

        } catch (McpException e) {
            long latency = System.currentTimeMillis() - start;
            auditLog.logFailure(identity, toolName, grantId, e.getErrorCode().getCode(), latency, arguments);
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            auditLog.logFailure(identity, toolName, grantId, "INTERNAL_ERROR", latency, arguments);
            throw new McpException(McpErrorCode.INTERNAL_ERROR, "Tool execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Internal dispatch (used by v2 subsystems). Skips scope validation but keeps policy + audit.
     */
    public Object dispatchInternal(CallerIdentity identity, String toolName, Map<String, Object> arguments) {
        Tool tool = resolveToolForCaller(identity, toolName);
        if (!policyEngine.evaluate(identity, tool)) {
            throw new McpException(McpErrorCode.FORBIDDEN_POLICY);
        }
        governance.preCheck(identity, tool);
        Object result = routeToHandler(identity, tool, arguments, null, null, null);
        governance.postCheck(identity, tool, true);
        return result;
    }

    private Tool resolveToolForCaller(CallerIdentity identity, String toolName) {
        List<Tool> candidates = toolRepo.findAccessibleByName(toolName, identity.getTenantId());
        if (candidates.isEmpty()) {
            throw new McpException(McpErrorCode.TOOL_NOT_FOUND, "Tool not found: " + toolName);
        }
        return candidates.getFirst();
    }

    private Object routeToHandler(CallerIdentity identity, Tool tool, Map<String, Object> arguments,
                                   String runId, String stepId, String conversationId) {
        // Check if tool is async
        if ("ASYNC".equals(tool.getExecutionMode())) {
            return asyncToolAdapter.dispatch(identity, tool, arguments, runId, stepId, conversationId);
        }

        // Sync tool execution
        return switch (tool.getSourceType()) {
            case "builtin" -> {
                BuiltinToolHandler handler = builtinRegistry.getHandler(tool.getToolName())
                    .orElseThrow(() -> new McpException(McpErrorCode.TOOL_NOT_FOUND,
                        "Built-in handler not found: " + tool.getToolName()));
                yield handler.execute(identity, arguments);
            }
            case "upstream_mcp" -> {
                UpstreamServer server = serverRepo.findById(tool.getSourceId())
                    .orElseThrow(() -> new McpException(McpErrorCode.UPSTREAM_UNHEALTHY,
                        "Upstream MCP server not found: " + tool.getSourceId()));
                checkUpstreamHealth(server);
                yield mcpProxy.forward(identity, server, tool, arguments);
            }
            case "upstream_rest" -> {
                UpstreamServer server = serverRepo.findById(tool.getSourceId())
                    .orElseThrow(() -> new McpException(McpErrorCode.UPSTREAM_UNHEALTHY,
                        "Upstream REST server not found: " + tool.getSourceId()));
                checkUpstreamHealth(server);
                yield restProxy.forward(identity, server, tool, arguments);
            }
            default -> throw new McpException(McpErrorCode.INTERNAL_ERROR,
                "Unknown source type: " + tool.getSourceType());
        };
    }

    private void checkUpstreamHealth(UpstreamServer server) {
        if ("unhealthy".equals(server.getHealthStatus())) {
            throw new McpException(McpErrorCode.UPSTREAM_UNHEALTHY,
                "Upstream server %s is unhealthy".formatted(server.getServerId()));
        }
    }
}

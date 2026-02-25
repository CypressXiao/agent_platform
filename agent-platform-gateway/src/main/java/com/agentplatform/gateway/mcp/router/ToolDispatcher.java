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

    /**
     * Dispatch a tool call from an external caller (Agent).
     */
    public Object dispatch(CallerIdentity identity, String toolName, Map<String, Object> arguments) {
        long start = System.currentTimeMillis();
        String grantId = null;

        try {
            // 1. Resolve tool
            Tool tool = resolveToolForCaller(identity, toolName);

            // 2. Scope validation
            scopeValidator.validate(identity, tool);

            // 3. Policy check
            if (!policyEngine.evaluate(identity, tool)) {
                throw new McpException(McpErrorCode.FORBIDDEN_POLICY,
                    "Policy denied access to tool: " + toolName);
            }

            // 4. Cross-tenant Grant check
            if (!identity.tenantId().equals(tool.getOwnerTid()) && !"system".equals(tool.getOwnerTid())) {
                Grant grant = grantEngine.check(identity.tenantId(), tool.getOwnerTid(), tool.getToolId());
                grantId = grant != null ? grant.getGrantId() : null;
            }

            // 5. Governance pre-check (rate limit, circuit breaker)
            governance.preCheck(identity, tool);

            // 6. Route to handler
            Object result = routeToHandler(identity, tool, arguments);

            // 7. Governance post-check
            governance.postCheck(identity, tool, true);

            // 8. Audit
            long latency = System.currentTimeMillis() - start;
            auditLog.logSuccess(identity, tool, grantId, latency);

            return result;

        } catch (McpException e) {
            long latency = System.currentTimeMillis() - start;
            auditLog.logFailure(identity, toolName, grantId, e.getErrorCode().getCode(), latency);
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            auditLog.logFailure(identity, toolName, grantId, "INTERNAL_ERROR", latency);
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
        Object result = routeToHandler(identity, tool, arguments);
        governance.postCheck(identity, tool, true);
        return result;
    }

    private Tool resolveToolForCaller(CallerIdentity identity, String toolName) {
        List<Tool> candidates = toolRepo.findAccessibleByName(toolName, identity.tenantId());
        if (candidates.isEmpty()) {
            throw new McpException(McpErrorCode.TOOL_NOT_FOUND, "Tool not found: " + toolName);
        }
        return candidates.getFirst();
    }

    private Object routeToHandler(CallerIdentity identity, Tool tool, Map<String, Object> arguments) {
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

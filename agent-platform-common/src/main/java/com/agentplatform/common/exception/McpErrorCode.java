package com.agentplatform.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum McpErrorCode {

    // AuthN/AuthZ
    UNAUTHORIZED("UNAUTHORIZED", "Authentication required", 401),
    FORBIDDEN_SCOPE("FORBIDDEN_SCOPE", "Insufficient scope", 403),
    FORBIDDEN_POLICY("FORBIDDEN_POLICY", "Policy denied access", 403),

    // Grant
    FORBIDDEN_NO_GRANT("FORBIDDEN_NO_GRANT", "No active grant for cross-tenant access", 403),
    GRANT_EXPIRED("GRANT_EXPIRED", "Grant has expired", 403),
    GRANT_REVOKED("GRANT_REVOKED", "Grant has been revoked", 403),

    // Upstream
    UPSTREAM_UNHEALTHY("UPSTREAM_UNHEALTHY", "Upstream server is unhealthy", 502),
    UPSTREAM_TIMEOUT("UPSTREAM_TIMEOUT", "Upstream server timed out", 504),

    // Tool
    TOOL_NOT_FOUND("TOOL_NOT_FOUND", "Tool not found or disabled", 404),

    // Governance
    RATE_LIMITED("RATE_LIMITED", "Rate limit exceeded", 429),
    CIRCUIT_OPEN("CIRCUIT_OPEN", "Circuit breaker is open", 503),

    // General
    BAD_REQUEST("BAD_REQUEST", "Invalid request", 400),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error", 500),

    // v2 Workflow
    GRAPH_NOT_FOUND("GRAPH_NOT_FOUND", "Graph template not found", 404),
    GRAPH_NOT_PUBLISHED("GRAPH_NOT_PUBLISHED", "Graph template not published", 400),
    WORKFLOW_NODE_FAILED("WORKFLOW_NODE_FAILED", "Workflow node execution failed", 500),
    WORKFLOW_TIMEOUT("WORKFLOW_TIMEOUT", "Workflow execution timed out", 504),

    // v2 Planner
    PLAN_NOT_FOUND("PLAN_NOT_FOUND", "Plan not found", 404),
    PLAN_TOOL_ACCESS_DENIED("PLAN_TOOL_ACCESS_DENIED", "Plan references inaccessible tools", 403),
    PLAN_EXECUTION_FAILED("PLAN_EXECUTION_FAILED", "Plan execution failed", 500),

    // v2 Memory
    MEMORY_QUOTA_EXCEEDED("MEMORY_QUOTA_EXCEEDED", "Memory storage quota exceeded", 429),
    MEMORY_NAMESPACE_NOT_FOUND("MEMORY_NAMESPACE_NOT_FOUND", "Memory namespace not found", 404),

    // v2 LLM
    LLM_MODEL_NOT_FOUND("LLM_MODEL_NOT_FOUND", "LLM model not found", 404),
    LLM_MODEL_FORBIDDEN("LLM_MODEL_FORBIDDEN", "Tenant not authorized for this model", 403),
    LLM_QUOTA_EXCEEDED("LLM_QUOTA_EXCEEDED", "LLM quota exceeded", 429),
    LLM_PROVIDER_ERROR("LLM_PROVIDER_ERROR", "LLM provider call failed", 502),
    LLM_PROMPT_FILTERED("LLM_PROMPT_FILTERED", "Prompt blocked by safety filter", 400);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;
}

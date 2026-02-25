package com.agentplatform.gateway.authz;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class ScopeValidator {

    /**
     * Validate that the caller has the required scopes for the tool.
     * Throws FORBIDDEN_SCOPE with WWW-Authenticate hint for step-up authorization.
     */
    public void validate(CallerIdentity identity, Tool tool) {
        List<String> requiredScopes = tool.getRequiredScopes();
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            return;
        }

        Set<String> callerScopes = identity.getScopes();
        List<String> missingScopes = requiredScopes.stream()
            .filter(s -> !callerScopes.contains(s))
            .toList();

        if (!missingScopes.isEmpty()) {
            String required = String.join(" ", missingScopes);
            throw new McpException(McpErrorCode.FORBIDDEN_SCOPE,
                "Insufficient scope. Required: " + required +
                ". Use step-up authorization to obtain the required scopes.");
        }
    }
}

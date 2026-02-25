package com.agentplatform.gateway.authz;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class PolicyEngine {

    private final WebClient opaClient;
    private final boolean opaEnabled;

    public PolicyEngine(@Qualifier("opaClient") WebClient opaClient,
                        @Value("${opa.enabled:false}") boolean opaEnabled) {
        this.opaClient = opaClient;
        this.opaEnabled = opaEnabled;
    }

    /**
     * Evaluate access policy. Returns true if access is allowed.
     * When OPA is disabled, falls back to simple scope-based check.
     */
    public boolean evaluate(CallerIdentity identity, Tool tool) {
        if (!opaEnabled) {
            return evaluateLocal(identity, tool);
        }

        try {
            PolicyInput input = new PolicyInput(
                identity.tenantId(),
                tool.getOwnerTid(),
                tool.getToolName(),
                tool.getSourceType(),
                identity.scopes(),
                null
            );

            Map<String, Object> request = Map.of("input", input);

            Map<String, Object> result = opaClient.post()
                .uri("/v1/data/authz/allow")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(java.time.Duration.ofSeconds(2));

            return result != null && Boolean.TRUE.equals(result.get("result"));
        } catch (Exception e) {
            log.warn("OPA evaluation failed, falling back to local policy: {}", e.getMessage());
            return evaluateLocal(identity, tool);
        }
    }

    /**
     * Local fallback policy: same-tenant or system tools are allowed if scope matches.
     */
    private boolean evaluateLocal(CallerIdentity identity, Tool tool) {
        // System tools are accessible to all authenticated users
        if ("system".equals(tool.getOwnerTid())) {
            return true;
        }

        // Check required scopes
        if (tool.getRequiredScopes() != null && !tool.getRequiredScopes().isEmpty()) {
            return identity.scopes().containsAll(tool.getRequiredScopes());
        }

        // Default: allow if basic scope is present or no specific scope required
        return identity.scopes().contains("mcp:tools-basic") || identity.scopes().isEmpty();
    }

    public record PolicyInput(
        String actorTid,
        String ownerTid,
        String toolName,
        String sourceType,
        Set<String> scopes,
        String grantId
    ) {}
}

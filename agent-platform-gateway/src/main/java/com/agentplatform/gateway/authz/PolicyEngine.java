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
                identity.getTenantId(),
                tool.getOwnerTid(),
                tool.getToolName(),
                tool.getSourceType(),
                identity.getScopes(),
                null
            );

            Map<String, Object> request = Map.of("input", input);

            // Check allow rule
            Map<String, Object> allowResult = opaClient.post()
                .uri("/v1/data/authz/allow")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(java.time.Duration.ofSeconds(2));

            boolean allowed = allowResult != null && Boolean.TRUE.equals(allowResult.get("result"));

            if (!allowed) {
                return false;
            }

            // Check deny rule — deny takes precedence over allow
            try {
                Map<String, Object> denyResult = opaClient.post()
                    .uri("/v1/data/authz/deny")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(java.time.Duration.ofSeconds(2));

                boolean denied = denyResult != null && Boolean.TRUE.equals(denyResult.get("result"));
                if (denied) {
                    log.info("OPA deny rule matched for actor={}, tool={}", identity.getTenantId(), tool.getToolName());
                    return false;
                }
            } catch (Exception e) {
                log.debug("OPA deny evaluation failed (treating as no deny): {}", e.getMessage());
            }

            return true;
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
            return identity.getScopes().containsAll(tool.getRequiredScopes());
        }

        // Default: allow if basic scope is present or no specific scope required
        return identity.getScopes().contains("mcp:tools-basic") || identity.getScopes().isEmpty();
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

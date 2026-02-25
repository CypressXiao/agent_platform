package com.agentplatform.gateway.mcp.router;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Grant;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.repository.GrantRepository;
import com.agentplatform.common.repository.ToolRepository;
import com.agentplatform.gateway.authz.PolicyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates tools visible to a caller:
 * 1. Own tenant tools (active)
 * 2. System built-in tools
 * 3. Shared tools via active Grants
 * 4. Policy filtering
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolAggregator {

    private final ToolRepository toolRepo;
    private final GrantRepository grantRepo;
    private final PolicyEngine policyEngine;

    @Cacheable(value = "toolsList", key = "#identity.tenantId()")
    public List<ToolView> listTools(CallerIdentity identity) {
        String tenantId = identity.tenantId();
        List<ToolView> result = new ArrayList<>();

        // 1. Own tenant tools
        List<Tool> ownTools = toolRepo.findByOwnerTidAndStatus(tenantId, "active");
        for (Tool tool : ownTools) {
            if (policyEngine.evaluate(identity, tool)) {
                result.add(toView(tool, false, null));
            }
        }

        // 2. System built-in tools
        List<Tool> systemTools = toolRepo.findSystemTools();
        for (Tool tool : systemTools) {
            if (policyEngine.evaluate(identity, tool)) {
                result.add(toView(tool, false, null));
            }
        }

        // 3. Shared tools via Grant
        List<Grant> grants = grantRepo.findActiveByGranteeTid(tenantId);
        for (Grant grant : grants) {
            if (grant.getTools() == null) continue;
            for (String toolId : grant.getTools()) {
                toolRepo.findById(toolId).ifPresent(tool -> {
                    if ("active".equals(tool.getStatus()) && policyEngine.evaluate(identity, tool)) {
                        result.add(toView(tool, true, grant.getGrantorTid()));
                    }
                });
            }
        }

        // Deduplicate by tool name (own tools take priority)
        Map<String, ToolView> deduped = new LinkedHashMap<>();
        for (ToolView view : result) {
            deduped.putIfAbsent(view.name(), view);
        }

        return new ArrayList<>(deduped.values());
    }

    private ToolView toView(Tool tool, boolean shared, String sharedFrom) {
        return new ToolView(
            tool.getToolName(),
            tool.getDescription(),
            tool.getInputSchema(),
            tool.getSourceType(),
            shared,
            sharedFrom
        );
    }

    public record ToolView(
        String name,
        String description,
        Map<String, Object> inputSchema,
        String sourceType,
        boolean shared,
        String sharedFrom
    ) {}
}

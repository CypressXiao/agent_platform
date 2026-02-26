package com.agentplatform.gateway.prompt;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Prompt 渲染工具
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.prompt.enabled", havingValue = "true")
public class PromptRenderTool implements BuiltinToolHandler {

    private final PromptService promptService;

    @Override
    public String toolName() {
        return "prompt_render";
    }

    @Override
    public String description() {
        return "Render a prompt template with variables. " +
               "Supports Mustache-style placeholders like {{variable_name}}.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "template_name", Map.of(
                    "type", "string",
                    "description", "Name of the prompt template"
                ),
                "version", Map.of(
                    "type", "integer",
                    "description", "Template version (optional, defaults to latest)"
                ),
                "variables", Map.of(
                    "type", "object",
                    "description", "Variables to substitute in the template"
                )
            ),
            "required", List.of("template_name", "variables")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String templateName = (String) arguments.get("template_name");
        Integer version = arguments.containsKey("version") 
            ? ((Number) arguments.get("version")).intValue() 
            : null;
        Map<String, Object> variables = (Map<String, Object>) arguments.get("variables");

        String tenantId = identity.getTenantId();

        try {
            String rendered;
            if (version != null) {
                rendered = promptService.render(tenantId, templateName, version, variables);
            } else {
                rendered = promptService.render(tenantId, templateName, variables);
            }

            return Map.of(
                "success", true,
                "template_name", templateName,
                "rendered", rendered
            );
        } catch (IllegalArgumentException e) {
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
}

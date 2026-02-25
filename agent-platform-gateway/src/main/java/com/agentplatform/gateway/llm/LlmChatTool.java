package com.agentplatform.gateway.llm;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LlmChatTool implements BuiltinToolHandler {

    private final LlmRouterService llmRouterService;

    @Override
    public String toolName() {
        return "llm_chat";
    }

    @Override
    public String description() {
        return "Unified LLM chat completion. Routes to the appropriate model provider with quota management and safety filtering.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "model", Map.of("type", "string", "description", "Model name or alias (e.g. gpt-4o, claude-3.5-sonnet, default)"),
                "messages", Map.of("type", "array", "description", "Chat messages list",
                    "items", Map.of("type", "object", "properties", Map.of(
                        "role", Map.of("type", "string", "enum", List.of("system", "user", "assistant")),
                        "content", Map.of("type", "string")
                    ))),
                "temperature", Map.of("type", "number", "description", "Temperature parameter", "default", 0.7),
                "max_tokens", Map.of("type", "integer", "description", "Max output tokens")
            ),
            "required", List.of("model", "messages")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String model = (String) arguments.getOrDefault("model", "default");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) arguments.get("messages");
        Double temperature = arguments.containsKey("temperature") ? ((Number) arguments.get("temperature")).doubleValue() : null;
        Integer maxTokens = arguments.containsKey("max_tokens") ? ((Number) arguments.get("max_tokens")).intValue() : null;

        return llmRouterService.chat(identity, model, messages, temperature, maxTokens);
    }
}

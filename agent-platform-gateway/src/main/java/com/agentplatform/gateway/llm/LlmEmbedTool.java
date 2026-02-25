package com.agentplatform.gateway.llm;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.mcp.upstream.VaultService;
import com.agentplatform.gateway.llm.model.LlmModelConfig;
import com.agentplatform.gateway.llm.model.LlmProvider;
import com.agentplatform.gateway.llm.repository.LlmModelConfigRepository;
import com.agentplatform.gateway.llm.repository.LlmProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LlmEmbedTool implements BuiltinToolHandler {

    private final LlmModelConfigRepository modelConfigRepo;
    private final LlmProviderRepository providerRepo;
    private final VaultService vault;
    private final WebClient.Builder webClientBuilder;

    @Override
    public String toolName() {
        return "llm_embed";
    }

    @Override
    public String description() {
        return "Generate text embeddings using a specified embedding model.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "model", Map.of("type", "string", "description", "Embedding model name"),
                "texts", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Texts to encode")
            ),
            "required", List.of("model", "texts")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String modelId = (String) arguments.get("model");
        List<String> texts = (List<String>) arguments.get("texts");

        LlmModelConfig config = modelConfigRepo.findByModelIdAndStatus(modelId, "active")
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_MODEL_NOT_FOUND, "Embedding model not found: " + modelId));

        LlmProvider provider = providerRepo.findById(config.getProviderId())
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_PROVIDER_ERROR, "Provider not found"));

        String apiKey = vault.getCredential(provider.getApiKeyRef());

        WebClient client = webClientBuilder.baseUrl(provider.getBaseUrl()).build();

        Map<String, Object> requestBody = Map.of(
            "model", config.getModelName(),
            "input", texts
        );

        Map<String, Object> response = client.post()
            .uri("/v1/embeddings")
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(30));

        if (response == null) {
            throw new McpException(McpErrorCode.LLM_PROVIDER_ERROR, "No response from embedding provider");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        List<List<Double>> embeddings = new ArrayList<>();
        if (data != null) {
            for (Map<String, Object> item : data) {
                embeddings.add((List<Double>) item.get("embedding"));
            }
        }

        return Map.of(
            "embeddings", embeddings,
            "model", config.getModelId(),
            "usage", response.getOrDefault("usage", Map.of())
        );
    }
}

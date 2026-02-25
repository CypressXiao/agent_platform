package com.agentplatform.gateway.llm;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.upstream.VaultService;
import com.agentplatform.gateway.llm.model.*;
import com.agentplatform.gateway.llm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * LLM Router Service — unified LLM access with model routing, quota management,
 * and usage tracking. Fetches API keys from Vault, never exposes them to callers.
 */
@Service
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LlmRouterService {

    private final LlmProviderRepository providerRepo;
    private final LlmModelConfigRepository modelConfigRepo;
    private final LlmTenantQuotaRepository quotaRepo;
    private final LlmUsageRecordRepository usageRepo;
    private final VaultService vault;
    private final WebClient.Builder webClientBuilder;
    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(CallerIdentity identity, String model, List<Map<String, Object>> messages,
                                     Double temperature, Integer maxTokens) {
        String tenantId = identity.tenantId();
        long start = System.currentTimeMillis();

        // 1. Resolve model
        LlmModelConfig modelConfig = resolveModel(tenantId, model);

        // 2. Quota check (RPM)
        checkQuota(tenantId, modelConfig.getModelId());

        // 3. Get provider and API key
        LlmProvider provider = providerRepo.findById(modelConfig.getProviderId())
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_PROVIDER_ERROR,
                "Provider not found: " + modelConfig.getProviderId()));

        String apiKey = vault.getCredential(provider.getApiKeyRef());

        // 4. Call LLM with fallback
        Map<String, Object> response = callWithFallback(provider, modelConfig, apiKey, messages, temperature, maxTokens);

        // 5. Record usage
        long latencyMs = System.currentTimeMillis() - start;
        recordUsage(tenantId, modelConfig, response, latencyMs);

        return response;
    }

    private LlmModelConfig resolveModel(String tenantId, String requestedModel) {
        if ("default".equals(requestedModel) || requestedModel == null) {
            // Find first active model
            List<LlmModelConfig> models = modelConfigRepo.findByStatus("active");
            if (models.isEmpty()) {
                throw new McpException(McpErrorCode.LLM_MODEL_NOT_FOUND, "No active models available");
            }
            return models.getFirst();
        }

        return modelConfigRepo.findByModelIdAndStatus(requestedModel, "active")
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_MODEL_NOT_FOUND,
                "Model not found or inactive: " + requestedModel));
    }

    private void checkQuota(String tenantId, String modelId) {
        // Check RPM via Redis sliding window
        String rpmKey = "llm_rpm:%s:%s".formatted(tenantId, modelId);
        try {
            Long count = redisTemplate.opsForValue().increment(rpmKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rpmKey, Duration.ofMinutes(1));
            }

            Optional<LlmTenantQuota> quota = quotaRepo.findByTenantIdAndModelId(tenantId, modelId);
            if (quota.isEmpty()) {
                quota = quotaRepo.findByTenantIdAndModelId(tenantId, "*");
            }

            if (quota.isPresent() && count != null && count > quota.get().getRpmLimit()) {
                throw new McpException(McpErrorCode.LLM_QUOTA_EXCEEDED,
                    "RPM limit exceeded: %d/%d".formatted(count, quota.get().getRpmLimit()));
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Quota check failed (allowing request): {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callWithFallback(LlmProvider provider, LlmModelConfig config,
                                                   String apiKey, List<Map<String, Object>> messages,
                                                   Double temperature, Integer maxTokens) {
        try {
            return callLlm(provider, config, apiKey, messages, temperature, maxTokens);
        } catch (Exception e) {
            log.warn("LLM call failed for model {}, trying fallback: {}", config.getModelId(), e.getMessage());

            if (config.getFallbackModelId() != null) {
                LlmModelConfig fallback = modelConfigRepo.findByModelIdAndStatus(config.getFallbackModelId(), "active")
                    .orElse(null);
                if (fallback != null) {
                    LlmProvider fallbackProvider = providerRepo.findById(fallback.getProviderId()).orElse(null);
                    if (fallbackProvider != null) {
                        String fallbackKey = vault.getCredential(fallbackProvider.getApiKeyRef());
                        return callLlm(fallbackProvider, fallback, fallbackKey, messages, temperature, maxTokens);
                    }
                }
            }

            throw new McpException(McpErrorCode.LLM_PROVIDER_ERROR,
                "LLM call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLlm(LlmProvider provider, LlmModelConfig config,
                                          String apiKey, List<Map<String, Object>> messages,
                                          Double temperature, Integer maxTokens) {
        WebClient client = webClientBuilder.baseUrl(provider.getBaseUrl()).build();

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModelName());
        requestBody.put("messages", messages);
        if (temperature != null) requestBody.put("temperature", temperature);
        if (maxTokens != null) requestBody.put("max_tokens", maxTokens);

        Map<String, Object> response = client.post()
            .uri("/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(60));

        if (response == null) {
            throw new McpException(McpErrorCode.LLM_PROVIDER_ERROR, "No response from LLM provider");
        }

        // Extract content from OpenAI-compatible response
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");

        String content = "";
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
            if (message != null) {
                content = (String) message.getOrDefault("content", "");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", content);
        result.put("model", config.getModelId());
        result.put("usage", usage != null ? usage : Map.of());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void recordUsage(String tenantId, LlmModelConfig config,
                              Map<String, Object> response, long latencyMs) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.getOrDefault("usage", Map.of());
            int promptTokens = usage.containsKey("prompt_tokens") ? ((Number) usage.get("prompt_tokens")).intValue() : 0;
            int completionTokens = usage.containsKey("completion_tokens") ? ((Number) usage.get("completion_tokens")).intValue() : 0;
            int totalTokens = promptTokens + completionTokens;

            BigDecimal cost = BigDecimal.ZERO;
            if (config.getInputPricePerMToken() != null) {
                cost = cost.add(config.getInputPricePerMToken()
                    .multiply(BigDecimal.valueOf(promptTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP));
            }
            if (config.getOutputPricePerMToken() != null) {
                cost = cost.add(config.getOutputPricePerMToken()
                    .multiply(BigDecimal.valueOf(completionTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP));
            }

            LlmUsageRecord record = LlmUsageRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .modelId(config.getModelId())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cost(cost)
                .latencyMs(latencyMs)
                .build();

            usageRepo.save(record);

            // Update monthly usage
            quotaRepo.findByTenantIdAndModelId(tenantId, config.getModelId())
                .or(() -> quotaRepo.findByTenantIdAndModelId(tenantId, "*"))
                .ifPresent(quota -> {
                    quota.setCurrentMonthUsage(quota.getCurrentMonthUsage() + totalTokens);
                    quotaRepo.save(quota);
                });
        } catch (Exception e) {
            log.warn("Failed to record LLM usage: {}", e.getMessage());
        }
    }
}

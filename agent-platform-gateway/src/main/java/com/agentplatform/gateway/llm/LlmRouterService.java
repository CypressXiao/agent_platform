package com.agentplatform.gateway.llm;

import com.agentplatform.gateway.llm.provider.LlmProviderHandler;
import com.agentplatform.gateway.llm.provider.LlmProviderHandler.TokenUsage;
import com.agentplatform.gateway.llm.provider.LlmProviderHandlerFactory;
import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.governance.RateLimitService;
import com.agentplatform.gateway.mcp.upstream.VaultService;
import com.agentplatform.gateway.llm.model.*;
import com.agentplatform.gateway.llm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
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
    private final RateLimitService rateLimitService;
    private final LlmProviderHandlerFactory handlerFactory;

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(CallerIdentity identity, String model, List<Map<String, Object>> messages,
                                     Double temperature, Integer maxTokens) {
        String tenantId = identity.getTenantId();
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

    private static final int DEFAULT_RPM_LIMIT = 60;
    private static final long RPM_WINDOW_MS = 60_000L;

    private void checkQuota(String tenantId, String modelId) {
        try {
            // 1. 查找配额：优先模型级，其次租户级通配符
            Optional<LlmTenantQuota> modelQuota = quotaRepo.findByTenantIdAndModelId(tenantId, modelId);
            Optional<LlmTenantQuota> globalQuota = quotaRepo.findByTenantIdAndModelId(tenantId, "*");

            // 2. 确定 RPM 限制（无配额时使用系统默认值）
            int modelRpmLimit = modelQuota.map(LlmTenantQuota::getRpmLimit).orElse(Integer.MAX_VALUE);
            int globalRpmLimit = globalQuota.map(LlmTenantQuota::getRpmLimit).orElse(DEFAULT_RPM_LIMIT);

            // 3. 检查模型级 RPM（如果有模型级配额）— 使用 Lua 滑动窗口
            if (modelQuota.isPresent()) {
                rateLimitService.check(tenantId, "llm:" + modelId, modelRpmLimit, RPM_WINDOW_MS);
            }

            // 4. 始终检查租户级全局 RPM（不可绕过）— 使用 Lua 滑动窗口
            rateLimitService.check(tenantId, "llm:*", globalRpmLimit, RPM_WINDOW_MS);

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
        LlmProviderHandler handler = handlerFactory.getHandler(provider.getName());
        
        WebClient client = webClientBuilder.baseUrl(provider.getBaseUrl()).build();
        
        Map<String, Object> requestBody = handler.buildRequestBody(config, messages, temperature, maxTokens);
        
        Map<String, Object> response = client.post()
            .uri(handler.getApiEndpoint())
            .header("Authorization", handler.buildAuthHeader(apiKey))
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(60));

        if (response == null) {
            throw new McpException(McpErrorCode.LLM_PROVIDER_ERROR, "No response from LLM provider");
        }

        String content = handler.extractContent(response);
        TokenUsage tokenUsage = handler.extractTokenUsage(response);

        Map<String, Object> result = new HashMap<>();
        result.put("content", content);
        result.put("model", config.getModelId());
        result.put("usage", Map.of(
            "prompt_tokens", tokenUsage.getPromptTokens(),
            "completion_tokens", tokenUsage.getCompletionTokens(),
            "total_tokens", tokenUsage.getTotalTokens()
        ));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void recordUsage(String tenantId, LlmModelConfig config,
                              Map<String, Object> response, long latencyMs) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.getOrDefault("usage", Map.of());
            int promptTokens = usage.containsKey("prompt_tokens") ? ((Number) usage.get("prompt_tokens")).intValue() : 0;
            int completionTokens = usage.containsKey("completion_tokens") ? ((Number) usage.get("completion_tokens")).intValue() : 0;
            int totalTokens = usage.containsKey("total_tokens") ? ((Number) usage.get("total_tokens")).intValue() : promptTokens + completionTokens;

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

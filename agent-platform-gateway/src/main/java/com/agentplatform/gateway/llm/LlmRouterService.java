package com.agentplatform.gateway.llm;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.governance.RateLimitService;
import com.agentplatform.gateway.llm.model.*;
import com.agentplatform.gateway.llm.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * LLM Router Service — unified LLM access with model routing, quota management,
 * and usage tracking. Now powered by Spring AI for unified token handling.
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
    private final RateLimitService rateLimitService;
    private final SpringAiLlmService springAiLlmService;

    /**
     * 🎯 使用 Spring AI 的统一聊天接口
     */
    public Map<String, Object> chat(CallerIdentity identity, String model, List<Map<String, Object>> messages,
                                     Double temperature, Integer maxTokens) {
        String tenantId = identity.getTenantId();
        long start = System.currentTimeMillis();

        // 1. Resolve model
        LlmModelConfig modelConfig = resolveModel(tenantId, model);

        // 2. Quota check (RPM)
        checkQuota(tenantId, modelConfig.getModelId());

        // 3. Get provider
        LlmProvider provider = providerRepo.findById(modelConfig.getProviderId())
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_PROVIDER_ERROR,
                "Provider not found: " + modelConfig.getProviderId()));

        // 4. Call LLM with fallback using Spring AI
        Map<String, Object> response = callWithFallback(provider, modelConfig, messages, temperature, maxTokens);

        // 5. Record usage (Spring AI automatically provides unified token info!)
        long latencyMs = System.currentTimeMillis() - start;
        recordUsage(tenantId, modelConfig, response, latencyMs);

        return response;
    }

    /**
     * 🎯 使用简化 Spring AI 适配器的嵌入接口
     */
    public List<float[]> embed(CallerIdentity identity, String model, List<String> texts) {
        String tenantId = identity.getTenantId();
        long start = System.currentTimeMillis();

        // 1. Resolve model
        LlmModelConfig modelConfig = resolveModel(tenantId, model);

        // 2. Quota check (RPM)
        checkQuota(tenantId, modelConfig.getModelId());

        // 3. Get provider
        LlmProvider provider = providerRepo.findById(modelConfig.getProviderId())
            .orElseThrow(() -> new McpException(McpErrorCode.LLM_PROVIDER_ERROR,
                "Provider not found: " + modelConfig.getProviderId()));

        // 4. Call embedding with simplified adapter
        Map<String, Object> response = callEmbeddingWithFallback(provider, modelConfig, texts);

        // 5. Record usage
        long latencyMs = System.currentTimeMillis() - start;
        recordEmbeddingUsage(tenantId, modelConfig, response, latencyMs);

        // 6. Extract embeddings
        List<List<Float>> embeddings = (List<List<Float>>) response.get("embeddings");
        if (embeddings != null && !embeddings.isEmpty()) {
            List<Float> firstEmbedding = embeddings.get(0);
            float[] result = new float[firstEmbedding.size()];
            for (int i = 0; i < firstEmbedding.size(); i++) {
                result[i] = firstEmbedding.get(i);
            }
            return List.of(result);
        }
        return List.of(new float[0]);
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

    /**
     * 🎯 使用简化 Spring AI 适配器的 Fallback 机制
     */
    private Map<String, Object> callWithFallback(LlmProvider provider, LlmModelConfig config,
                                                   List<Map<String, Object>> messages,
                                                   Double temperature, Integer maxTokens) {
        try {
            return springAiLlmService.callChat(provider, config.getModelName(), messages, temperature, maxTokens);
        } catch (Exception e) {
            log.warn("LLM call failed for model {}, trying fallback: {}", config.getModelId(), e.getMessage());

            if (config.getFallbackModelId() != null) {
                LlmModelConfig fallback = modelConfigRepo.findByModelIdAndStatus(config.getFallbackModelId(), "active")
                    .orElse(null);
                if (fallback != null) {
                    LlmProvider fallbackProvider = providerRepo.findById(fallback.getProviderId()).orElse(null);
                    if (fallbackProvider != null) {
                        return springAiLlmService.callChat(fallbackProvider, fallback.getModelName(), messages, temperature, maxTokens);
                    }
                }
            }

            throw new McpException(McpErrorCode.LLM_PROVIDER_ERROR,
                "LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 🎯 使用简化 Spring AI 适配器调用嵌入
     */
    private Map<String, Object> callEmbeddingWithFallback(LlmProvider provider, LlmModelConfig config, List<String> texts) {
        // RAG embedding is handled by separate implementation, delegate to that
        throw new UnsupportedOperationException("Embedding is handled by separate RAG implementation");
    }

    /**
     * 🎯 记录使用量 - Spring AI 自动提供统一的 Token 信息！
     */
    private void recordUsage(String tenantId, LlmModelConfig config, Map<String, Object> response, long latencyMs) {
        try {
            // 🎯 Spring AI 已经在 convertToUnifiedFormat 中提供了统一的 Token 信息
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            
            int promptTokens = (Integer) usage.getOrDefault("prompt_tokens", 0);
            int completionTokens = (Integer) usage.getOrDefault("completion_tokens", 0);
            int totalTokens = (Integer) usage.getOrDefault("total_tokens", 0);

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

    /**
     * 🎯 记录嵌入使用量 - 使用统一格式！
     */
    private void recordEmbeddingUsage(String tenantId, LlmModelConfig config, Map<String, Object> response, long latencyMs) {
        try {
            // 🎯 使用统一格式的 Token 信息
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            
            int totalTokens = (Integer) usage.getOrDefault("total_tokens", 0);

            BigDecimal cost = BigDecimal.ZERO;
            if (config.getInputPricePerMToken() != null) {
                cost = cost.add(config.getInputPricePerMToken()
                    .multiply(BigDecimal.valueOf(totalTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP));
            }

            LlmUsageRecord record = LlmUsageRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .modelId(config.getModelId())
                .promptTokens(totalTokens)
                .completionTokens(0)
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
            log.warn("Failed to record embedding usage: {}", e.getMessage());
        }
    }
}

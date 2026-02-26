package com.agentplatform.gateway.llm;

import com.agentplatform.gateway.llm.model.*;
import com.agentplatform.gateway.llm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/llm")
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LlmAdminController {

    private final LlmProviderRepository providerRepo;
    private final LlmModelConfigRepository modelConfigRepo;
    private final LlmTenantQuotaRepository quotaRepo;
    private final LlmUsageRecordRepository usageRepo;

    @PostMapping("/providers")
    public ResponseEntity<LlmProvider> createProvider(@RequestBody LlmProvider provider) {
        if (provider.getProviderId() == null) provider.setProviderId(UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(providerRepo.save(provider));
    }

    @GetMapping("/providers")
    public ResponseEntity<List<LlmProvider>> listProviders() {
        return ResponseEntity.ok(providerRepo.findAll());
    }

    @PostMapping("/models")
    public ResponseEntity<LlmModelConfig> createModel(@RequestBody LlmModelConfig config) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modelConfigRepo.save(config));
    }

    @GetMapping("/models")
    public ResponseEntity<List<LlmModelConfig>> listModels() {
        return ResponseEntity.ok(modelConfigRepo.findAll());
    }

    @PutMapping("/quotas/{tenantId}")
    public ResponseEntity<LlmTenantQuota> setQuota(@PathVariable String tenantId,
                                                     @RequestBody LlmTenantQuota quota) {
        quota.setTenantId(tenantId);
        String modelId = quota.getModelId() != null ? quota.getModelId() : "*";
        quota.setModelId(modelId);

        // 查找已存在的配额并更新，否则创建新配额
        LlmTenantQuota existing = quotaRepo.findByTenantIdAndModelId(tenantId, modelId).orElse(null);
        if (existing != null) {
            existing.setRpmLimit(quota.getRpmLimit() != null ? quota.getRpmLimit() : existing.getRpmLimit());
            existing.setTpmLimit(quota.getTpmLimit() != null ? quota.getTpmLimit() : existing.getTpmLimit());
            existing.setMonthlyTokenBudget(quota.getMonthlyTokenBudget() != null ? quota.getMonthlyTokenBudget() : existing.getMonthlyTokenBudget());
            return ResponseEntity.ok(quotaRepo.save(existing));
        }

        if (quota.getQuotaId() == null) quota.setQuotaId(UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(quotaRepo.save(quota));
    }

    @PatchMapping("/quotas/{tenantId}/{modelId}")
    public ResponseEntity<LlmTenantQuota> updateQuota(@PathVariable String tenantId,
                                                        @PathVariable String modelId,
                                                        @RequestBody Map<String, Object> updates) {
        return quotaRepo.findByTenantIdAndModelId(tenantId, modelId)
            .map(quota -> {
                if (updates.containsKey("rpm_limit")) quota.setRpmLimit(((Number) updates.get("rpm_limit")).intValue());
                if (updates.containsKey("tpm_limit")) quota.setTpmLimit(((Number) updates.get("tpm_limit")).intValue());
                if (updates.containsKey("monthly_token_budget")) quota.setMonthlyTokenBudget(((Number) updates.get("monthly_token_budget")).longValue());
                if (updates.containsKey("current_month_usage")) quota.setCurrentMonthUsage(((Number) updates.get("current_month_usage")).longValue());
                return ResponseEntity.ok(quotaRepo.save(quota));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/quotas/{tenantId}/{modelId}")
    public ResponseEntity<Void> deleteQuota(@PathVariable String tenantId, @PathVariable String modelId) {
        return quotaRepo.findByTenantIdAndModelId(tenantId, modelId)
            .map(quota -> {
                quotaRepo.delete(quota);
                return ResponseEntity.noContent().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/quotas/{tenantId}")
    public ResponseEntity<List<LlmTenantQuota>> getQuotas(@PathVariable String tenantId) {
        return ResponseEntity.ok(quotaRepo.findByTenantId(tenantId));
    }

    @PostMapping("/quotas/{tenantId}/reset")
    public ResponseEntity<LlmTenantQuota> resetMonthlyUsage(@PathVariable String tenantId,
                                                              @RequestParam(defaultValue = "*") String modelId) {
        return quotaRepo.findByTenantIdAndModelId(tenantId, modelId)
            .map(quota -> {
                quota.setCurrentMonthUsage(0L);
                quota.setResetAt(java.time.Instant.now());
                return ResponseEntity.ok(quotaRepo.save(quota));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(@RequestParam String tenantId,
                                                         @RequestParam(required = false) java.time.Instant from,
                                                         @RequestParam(required = false) java.time.Instant to) {
        if (from == null) from = java.time.Instant.now().minus(java.time.Duration.ofDays(30));
        if (to == null) to = java.time.Instant.now();

        var records = usageRepo.findByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long totalTokens = records.stream().mapToLong(r -> r.getTotalTokens() != null ? r.getTotalTokens() : 0).sum();
        double totalCost = records.stream()
            .filter(r -> r.getCost() != null)
            .mapToDouble(r -> r.getCost().doubleValue())
            .sum();

        return ResponseEntity.ok(Map.of(
            "tenant_id", tenantId,
            "total_requests", records.size(),
            "total_tokens", totalTokens,
            "total_cost", totalCost,
            "period_from", from.toString(),
            "period_to", to.toString()
        ));
    }
}

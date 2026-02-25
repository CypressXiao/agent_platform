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
        if (quota.getQuotaId() == null) quota.setQuotaId(UUID.randomUUID().toString());
        return ResponseEntity.ok(quotaRepo.save(quota));
    }

    @GetMapping("/quotas/{tenantId}")
    public ResponseEntity<List<LlmTenantQuota>> getQuotas(@PathVariable String tenantId) {
        return ResponseEntity.ok(quotaRepo.findByTenantId(tenantId));
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(@RequestParam String tenantId,
                                                         @RequestParam(required = false) java.time.Instant from,
                                                         @RequestParam(required = false) java.time.Instant to) {
        if (from == null) from = java.time.Instant.now().minus(java.time.Duration.ofDays(30));
        if (to == null) to = java.time.Instant.now();

        var records = usageRepo.findByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long totalTokens = records.stream().mapToLong(LlmUsageRecord::getTotalTokens).sum();
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

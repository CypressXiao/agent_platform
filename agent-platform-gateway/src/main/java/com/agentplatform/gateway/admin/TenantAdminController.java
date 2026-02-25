package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.Tenant;
import com.agentplatform.common.repository.TenantRepository;
import com.agentplatform.gateway.authn.TenantValidationFilter;
import com.agentplatform.gateway.llm.model.LlmTenantQuota;
import com.agentplatform.gateway.llm.repository.LlmTenantQuotaRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
@Slf4j
public class TenantAdminController {

    private final TenantRepository tenantRepo;
    private final TenantValidationFilter tenantValidationFilter;

    @Autowired(required = false)
    private LlmTenantQuotaRepository llmQuotaRepo;

    @PostMapping
    public ResponseEntity<Tenant> createTenant(@Valid @RequestBody Tenant tenant) {
        Tenant saved = tenantRepo.save(tenant);
        createDefaultLlmQuota(saved.getTid());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    private void createDefaultLlmQuota(String tenantId) {
        if (llmQuotaRepo == null) {
            log.debug("LLM module not enabled, skipping default quota creation");
            return;
        }
        try {
            LlmTenantQuota defaultQuota = LlmTenantQuota.builder()
                .quotaId(UUID.randomUUID().toString())
                .tenantId(tenantId)
                .modelId("*")
                .rpmLimit(60)
                .tpmLimit(100000)
                .monthlyTokenBudget(10000000L)
                .currentMonthUsage(0L)
                .build();
            llmQuotaRepo.save(defaultQuota);
            log.info("Created default LLM quota for tenant: {}", tenantId);
        } catch (Exception e) {
            log.warn("Failed to create default LLM quota for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<Tenant>> listTenants() {
        return ResponseEntity.ok(tenantRepo.findAll());
    }

    @GetMapping("/{tid}")
    public ResponseEntity<Tenant> getTenant(@PathVariable String tid) {
        return tenantRepo.findById(tid)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{tid}")
    public ResponseEntity<Tenant> updateTenant(@PathVariable String tid, @RequestBody Tenant update) {
        return tenantRepo.findById(tid)
            .map(existing -> {
                if (update.getName() != null) existing.setName(update.getName());
                if (update.getStatus() != null) existing.setStatus(update.getStatus());
                if (update.getQuotaConfig() != null) existing.setQuotaConfig(update.getQuotaConfig());
                Tenant saved = tenantRepo.save(existing);
                // 状态变更时立即使缓存失效
                tenantValidationFilter.evictTenant(tid);
                return ResponseEntity.ok(saved);
            })
            .orElse(ResponseEntity.notFound().build());
    }
}

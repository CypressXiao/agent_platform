package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.Tenant;
import com.agentplatform.common.repository.TenantRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantRepository tenantRepo;

    @PostMapping
    public ResponseEntity<Tenant> createTenant(@Valid @RequestBody Tenant tenant) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tenantRepo.save(tenant));
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
                return ResponseEntity.ok(tenantRepo.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }
}

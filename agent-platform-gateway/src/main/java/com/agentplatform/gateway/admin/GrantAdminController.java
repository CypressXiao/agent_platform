package com.agentplatform.gateway.admin;

import com.agentplatform.common.dto.CreateGrantRequest;
import com.agentplatform.common.model.Grant;
import com.agentplatform.common.repository.GrantRepository;
import com.agentplatform.gateway.authz.GrantEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/grants")
@RequiredArgsConstructor
public class GrantAdminController {

    private final GrantRepository grantRepo;
    private final GrantEngine grantEngine;

    @PostMapping
    public ResponseEntity<Grant> createGrant(@Valid @RequestBody CreateGrantRequest request) {
        Grant grant = Grant.builder()
            .grantId(UUID.randomUUID().toString())
            .grantorTid(request.getGrantorTid())
            .granteeTid(request.getGranteeTid())
            .tools(request.getTools())
            .scopes(request.getScopes())
            .constraints(request.getConstraints())
            .expiresAt(request.getExpiresAt())
            .build();

        grant = grantRepo.save(grant);
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @GetMapping
    public ResponseEntity<List<Grant>> listGrants(
            @RequestParam(required = false) String grantorTid,
            @RequestParam(required = false) String granteeTid) {
        if (grantorTid != null) {
            return ResponseEntity.ok(grantRepo.findByGrantorTid(grantorTid));
        }
        if (granteeTid != null) {
            return ResponseEntity.ok(grantRepo.findByGranteeTid(granteeTid));
        }
        return ResponseEntity.ok(grantRepo.findAll());
    }

    @GetMapping("/{grantId}")
    public ResponseEntity<Grant> getGrant(@PathVariable String grantId) {
        return grantRepo.findById(grantId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{grantId}/revoke")
    public ResponseEntity<Void> revokeGrant(@PathVariable String grantId,
                                             @RequestParam(defaultValue = "admin_revoked") String reason) {
        grantEngine.revoke(grantId, reason);
        return ResponseEntity.noContent().build();
    }
}

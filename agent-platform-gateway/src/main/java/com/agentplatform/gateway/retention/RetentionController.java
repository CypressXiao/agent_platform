package com.agentplatform.gateway.retention;

import com.agentplatform.common.model.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据保留策略 API
 */
@RestController
@RequestMapping("/api/v1/retention")
@RequiredArgsConstructor
@Tag(name = "Retention", description = "数据保留策略管理")
public class RetentionController {

    private final RetentionService retentionService;

    @PostMapping("/policies")
    @Operation(summary = "创建保留策略")
    public ResponseEntity<RetentionPolicy> createPolicy(
            @RequestAttribute CallerIdentity identity,
            @RequestBody CreatePolicyRequest request) {
        RetentionPolicy policy = retentionService.createPolicy(
            identity,
            request.getName(),
            request.getDataType(),
            request.getRetentionDays(),
            request.getArchiveEnabled(),
            request.getArchiveTarget(),
            request.getArchiveFormat()
        );
        return ResponseEntity.ok(policy);
    }

    @GetMapping("/policies")
    @Operation(summary = "列出保留策略")
    public ResponseEntity<List<RetentionPolicy>> listPolicies(
            @RequestAttribute CallerIdentity identity) {
        return ResponseEntity.ok(retentionService.listPolicies(identity));
    }

    @PutMapping("/policies/{policyId}")
    @Operation(summary = "更新保留策略")
    public ResponseEntity<RetentionPolicy> updatePolicy(
            @PathVariable String policyId,
            @RequestBody UpdatePolicyRequest request) {
        RetentionPolicy policy = retentionService.updatePolicy(
            policyId,
            request.getRetentionDays(),
            request.getArchiveEnabled(),
            request.getArchiveTarget()
        );
        return ResponseEntity.ok(policy);
    }

    @DeleteMapping("/policies/{policyId}")
    @Operation(summary = "禁用保留策略")
    public ResponseEntity<Void> disablePolicy(@PathVariable String policyId) {
        retentionService.disablePolicy(policyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/policies/{policyId}/execute")
    @Operation(summary = "手动执行保留策略")
    public ResponseEntity<Map<String, Object>> executePolicy(@PathVariable String policyId) {
        RetentionPolicy policy = retentionService.listPolicies(null).stream()
            .filter(p -> p.getPolicyId().equals(policyId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Policy not found"));

        int cleaned = retentionService.executePolicy(policy);
        return ResponseEntity.ok(Map.of(
            "policyId", policyId,
            "cleanedRecords", cleaned
        ));
    }

    @GetMapping("/stats/{tenantId}")
    @Operation(summary = "获取数据统计")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String tenantId) {
        return ResponseEntity.ok(retentionService.getDataStats(tenantId));
    }

    @lombok.Data
    public static class CreatePolicyRequest {
        private String name;
        private String dataType;
        private Integer retentionDays;
        private Boolean archiveEnabled;
        private String archiveTarget;
        private String archiveFormat;
    }

    @lombok.Data
    public static class UpdatePolicyRequest {
        private Integer retentionDays;
        private Boolean archiveEnabled;
        private String archiveTarget;
    }
}

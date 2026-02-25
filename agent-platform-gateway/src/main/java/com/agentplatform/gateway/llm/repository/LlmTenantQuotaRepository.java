package com.agentplatform.gateway.llm.repository;

import com.agentplatform.gateway.llm.model.LlmTenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmTenantQuotaRepository extends JpaRepository<LlmTenantQuota, String> {
    List<LlmTenantQuota> findByTenantId(String tenantId);
    Optional<LlmTenantQuota> findByTenantIdAndModelId(String tenantId, String modelId);
}

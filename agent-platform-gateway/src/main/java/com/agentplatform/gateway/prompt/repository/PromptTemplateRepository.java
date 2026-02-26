package com.agentplatform.gateway.prompt.repository;

import com.agentplatform.gateway.prompt.model.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, String> {

    List<PromptTemplate> findByTenantIdAndStatus(String tenantId, String status);

    Optional<PromptTemplate> findByTenantIdAndNameAndStatus(String tenantId, String name, String status);

    Optional<PromptTemplate> findByTenantIdAndNameAndVersion(String tenantId, String name, Integer version);

    List<PromptTemplate> findByTenantIdAndName(String tenantId, String name);

    Optional<PromptTemplate> findFirstByTenantIdAndNameAndStatusOrderByVersionDesc(
        String tenantId, String name, String status);
}

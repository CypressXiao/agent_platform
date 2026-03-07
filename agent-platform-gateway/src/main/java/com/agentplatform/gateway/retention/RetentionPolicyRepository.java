package com.agentplatform.gateway.retention;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据保留策略仓库
 */
@Repository
public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, String> {

    List<RetentionPolicy> findByTenantIdAndStatus(String tenantId, String status);

    List<RetentionPolicy> findByStatus(String status);

    Optional<RetentionPolicy> findByTenantIdAndDataTypeAndStatus(
        String tenantId, String dataType, String status);
}

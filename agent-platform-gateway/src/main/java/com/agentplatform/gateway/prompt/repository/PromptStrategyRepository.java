package com.agentplatform.gateway.prompt.repository;

import com.agentplatform.gateway.prompt.model.PromptStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Prompt 策略仓库
 */
@Repository
public interface PromptStrategyRepository extends JpaRepository<PromptStrategy, String> {

    List<PromptStrategy> findByTenantIdAndStatusOrderByPriorityDesc(String tenantId, String status);

    List<PromptStrategy> findByTenantIdAndNameAndStatus(String tenantId, String name, String status);
}

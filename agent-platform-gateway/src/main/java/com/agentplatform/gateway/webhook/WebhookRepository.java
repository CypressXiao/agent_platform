package com.agentplatform.gateway.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Webhook 配置仓库
 */
@Repository
public interface WebhookRepository extends JpaRepository<WebhookConfig, String> {

    List<WebhookConfig> findByTenantIdAndStatus(String tenantId, String status);

    List<WebhookConfig> findByTenantIdAndStatusAndEventTypesContaining(
        String tenantId, String status, String eventType);
}

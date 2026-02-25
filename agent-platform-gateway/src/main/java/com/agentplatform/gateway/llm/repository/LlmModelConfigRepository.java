package com.agentplatform.gateway.llm.repository;

import com.agentplatform.gateway.llm.model.LlmModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmModelConfigRepository extends JpaRepository<LlmModelConfig, String> {
    List<LlmModelConfig> findByProviderId(String providerId);
    List<LlmModelConfig> findByStatus(String status);
    Optional<LlmModelConfig> findByModelIdAndStatus(String modelId, String status);
}

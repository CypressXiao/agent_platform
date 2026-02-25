package com.agentplatform.gateway.llm.repository;

import com.agentplatform.gateway.llm.model.LlmProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, String> {
    List<LlmProvider> findByStatus(String status);
}

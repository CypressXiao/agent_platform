package com.agentplatform.gateway.llm.repository;

import com.agentplatform.gateway.llm.model.LlmUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface LlmUsageRecordRepository extends JpaRepository<LlmUsageRecord, String> {
    List<LlmUsageRecord> findByTenantIdAndCreatedAtBetween(String tenantId, Instant from, Instant to);

    @Query("SELECT SUM(r.totalTokens) FROM LlmUsageRecord r WHERE r.tenantId = :tenantId AND r.createdAt >= :since")
    Long sumTokensSince(@Param("tenantId") String tenantId, @Param("since") Instant since);
}

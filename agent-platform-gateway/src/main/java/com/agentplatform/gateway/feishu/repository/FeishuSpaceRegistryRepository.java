package com.agentplatform.gateway.feishu.repository;

import com.agentplatform.gateway.feishu.model.FeishuSpaceRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeishuSpaceRegistryRepository extends JpaRepository<FeishuSpaceRegistry, Long> {

    Optional<FeishuSpaceRegistry> findBySpaceToken(String spaceToken);

    boolean existsBySpaceToken(String spaceToken);

    List<FeishuSpaceRegistry> findByTenantAndSyncEnabledTrue(String tenant);

    List<FeishuSpaceRegistry> findBySyncEnabledTrueAndStatus(String status);

    Page<FeishuSpaceRegistry> findByTenant(String tenant, Pageable pageable);

    @Query("SELECT s FROM FeishuSpaceRegistry s WHERE s.syncEnabled = true AND s.status = 'ACTIVE' AND " +
           "(s.lastScanAt IS NULL OR s.lastScanAt < :threshold)")
    List<FeishuSpaceRegistry> findSpacesNeedingScan(@Param("threshold") Instant threshold);

    @Modifying
    @Query("UPDATE FeishuSpaceRegistry s SET s.lastScanAt = :scanAt, s.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE s.spaceToken = :spaceToken")
    int updateLastScanAt(@Param("spaceToken") String spaceToken, @Param("scanAt") Instant scanAt);

    @Modifying
    @Query("UPDATE FeishuSpaceRegistry s SET s.status = :status, s.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE s.spaceToken = :spaceToken")
    int updateStatus(@Param("spaceToken") String spaceToken, @Param("status") String status);

    long countByTenantAndSyncEnabledTrue(String tenant);
}

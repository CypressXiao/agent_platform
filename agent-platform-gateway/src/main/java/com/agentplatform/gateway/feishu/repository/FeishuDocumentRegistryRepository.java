package com.agentplatform.gateway.feishu.repository;

import com.agentplatform.gateway.feishu.model.FeishuDocumentRegistry;
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
public interface FeishuDocumentRegistryRepository extends JpaRepository<FeishuDocumentRegistry, Long> {

    Optional<FeishuDocumentRegistry> findByDocToken(String docToken);

    boolean existsByDocToken(String docToken);

    List<FeishuDocumentRegistry> findByTenantAndStatus(String tenant, String status);

    List<FeishuDocumentRegistry> findByTenantAndSceneAndStatus(String tenant, String scene, String status);

    Page<FeishuDocumentRegistry> findByTenant(String tenant, Pageable pageable);

    Page<FeishuDocumentRegistry> findByTenantAndScene(String tenant, String scene, Pageable pageable);

    List<FeishuDocumentRegistry> findBySpaceIdAndStatus(String spaceId, String status);

    List<FeishuDocumentRegistry> findByParentTokenAndStatus(String parentToken, String status);

    @Query("SELECT d FROM FeishuDocumentRegistry d WHERE d.status = 'ACTIVE' AND " +
           "(d.lastSyncAt IS NULL OR d.lastSyncAt < :threshold)")
    List<FeishuDocumentRegistry> findDocumentsNeedingSync(@Param("threshold") Instant threshold);

    @Query("SELECT d FROM FeishuDocumentRegistry d WHERE d.status = 'ACTIVE' AND d.tenant = :tenant AND " +
           "(d.lastSyncAt IS NULL OR d.lastSyncAt < :threshold)")
    List<FeishuDocumentRegistry> findDocumentsNeedingSyncByTenant(
            @Param("tenant") String tenant,
            @Param("threshold") Instant threshold);

    @Modifying
    @Query("UPDATE FeishuDocumentRegistry d SET d.status = :status, d.updatedAt = CURRENT_TIMESTAMP WHERE d.docToken = :docToken")
    int updateStatus(@Param("docToken") String docToken, @Param("status") String status);

    @Modifying
    @Query("UPDATE FeishuDocumentRegistry d SET d.lastRevision = :revision, d.lastSyncAt = :syncAt, " +
           "d.updatedAt = CURRENT_TIMESTAMP WHERE d.docToken = :docToken")
    int updateSyncInfo(@Param("docToken") String docToken,
                       @Param("revision") String revision,
                       @Param("syncAt") Instant syncAt);

    long countByTenantAndStatus(String tenant, String status);

    long countByTenantAndSceneAndStatus(String tenant, String scene, String status);
}

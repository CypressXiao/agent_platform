package com.agentplatform.gateway.memory.repository;

import com.agentplatform.gateway.memory.model.MemoryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, String> {

    Page<MemoryEntry> findByOwnerTidAndAgentIdAndNamespace(String ownerTid, String agentId,
                                                            String namespace, Pageable pageable);

    long countByOwnerTidAndNamespace(String ownerTid, String namespace);

    @Modifying
    @Query("DELETE FROM MemoryEntry m WHERE m.ownerTid = :ownerTid AND m.agentId = :agentId AND m.namespace = :namespace")
    void clearNamespace(@Param("ownerTid") String ownerTid,
                        @Param("agentId") String agentId,
                        @Param("namespace") String namespace);

    @Modifying
    @Query("DELETE FROM MemoryEntry m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpired();
}

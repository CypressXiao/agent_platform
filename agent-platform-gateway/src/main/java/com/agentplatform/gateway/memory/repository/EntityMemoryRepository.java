package com.agentplatform.gateway.memory.repository;

import com.agentplatform.gateway.memory.model.EntityMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EntityMemoryRepository extends JpaRepository<EntityMemory, String> {

    List<EntityMemory> findByOwnerTidAndAgentId(String ownerTid, String agentId);

    List<EntityMemory> findByOwnerTidAndAgentIdAndEntityType(String ownerTid, String agentId, String entityType);

    Optional<EntityMemory> findByOwnerTidAndAgentIdAndEntityTypeAndEntityKey(
        String ownerTid, String agentId, String entityType, String entityKey);

    long countByOwnerTidAndAgentId(String ownerTid, String agentId);
}

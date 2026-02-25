package com.agentplatform.gateway.memory.repository;

import com.agentplatform.gateway.memory.model.MemoryNamespace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemoryNamespaceRepository extends JpaRepository<MemoryNamespace, String> {
    List<MemoryNamespace> findByOwnerTid(String ownerTid);
    Optional<MemoryNamespace> findByOwnerTidAndName(String ownerTid, String name);
}

package com.agentplatform.common.repository;

import com.agentplatform.common.model.UpstreamServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface UpstreamServerRepository extends JpaRepository<UpstreamServer, String> {

    List<UpstreamServer> findByOwnerTid(String ownerTid);

    List<UpstreamServer> findByServerType(String serverType);

    @Modifying
    @Query("UPDATE UpstreamServer s SET s.healthStatus = :status, s.lastHealthCheck = :now WHERE s.serverId = :serverId")
    void updateHealthStatus(@Param("serverId") String serverId,
                            @Param("status") String status,
                            @Param("now") Instant now);

    default void updateHealthStatus(String serverId, String status) {
        updateHealthStatus(serverId, status, Instant.now());
    }
}

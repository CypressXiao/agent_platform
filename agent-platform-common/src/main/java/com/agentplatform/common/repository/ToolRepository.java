package com.agentplatform.common.repository;

import com.agentplatform.common.model.Tool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ToolRepository extends JpaRepository<Tool, String> {

    List<Tool> findByOwnerTidAndStatus(String ownerTid, String status);

    List<Tool> findBySourceIdAndStatus(String sourceId, String status);

    Optional<Tool> findByToolNameAndOwnerTid(String toolName, String ownerTid);

    @Query("SELECT t FROM Tool t WHERE t.toolName = :toolName AND t.status = 'active' " +
           "AND (t.ownerTid = :ownerTid OR t.ownerTid = 'system') ORDER BY t.priority DESC")
    List<Tool> findAccessibleByName(@Param("toolName") String toolName,
                                    @Param("ownerTid") String ownerTid);

    @Query("SELECT t FROM Tool t WHERE t.status = 'active' AND t.ownerTid = 'system'")
    List<Tool> findSystemTools();

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteBySourceId(String sourceId);
}

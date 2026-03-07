package com.agentplatform.common.repository;

import com.agentplatform.common.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    Page<AuditLog> findByActorTid(String actorTid, Pageable pageable);

    Page<AuditLog> findByToolName(String toolName, Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE a.actorTid = :tid AND a.timestamp BETWEEN :from AND :to")
    Page<AuditLog> findByTidAndTimeRange(@Param("tid") String tid,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to,
                                          Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE a.traceId = :traceId ORDER BY a.timestamp ASC")
    java.util.List<AuditLog> findByTraceId(@Param("traceId") String traceId);

    long countByActorTid(String actorTid);

    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AuditLog a WHERE a.actorTid = :tid AND a.timestamp < :cutoff")
    int deleteByActorTidAndTimestampBefore(@Param("tid") String tid, @Param("cutoff") Instant cutoff);
}

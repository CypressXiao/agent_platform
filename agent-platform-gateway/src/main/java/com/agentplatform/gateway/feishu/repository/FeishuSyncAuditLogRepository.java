package com.agentplatform.gateway.feishu.repository;

import com.agentplatform.gateway.feishu.model.FeishuSyncAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface FeishuSyncAuditLogRepository extends JpaRepository<FeishuSyncAuditLog, Long> {

    List<FeishuSyncAuditLog> findByDocToken(String docToken);

    Page<FeishuSyncAuditLog> findByDocToken(String docToken, Pageable pageable);

    List<FeishuSyncAuditLog> findByTaskId(String taskId);

    Page<FeishuSyncAuditLog> findByOperation(String operation, Pageable pageable);

    @Query("SELECT a FROM FeishuSyncAuditLog a WHERE a.createdAt >= :start AND a.createdAt <= :end " +
           "ORDER BY a.createdAt DESC")
    Page<FeishuSyncAuditLog> findByTimeRange(@Param("start") Instant start,
                                              @Param("end") Instant end,
                                              Pageable pageable);

    @Query("SELECT a FROM FeishuSyncAuditLog a WHERE a.success = false AND a.createdAt >= :since " +
           "ORDER BY a.createdAt DESC")
    List<FeishuSyncAuditLog> findRecentFailures(@Param("since") Instant since);

    long countByOperationAndSuccessAndCreatedAtAfter(String operation, Boolean success, Instant after);

    @Query("SELECT a.operation, COUNT(a), SUM(CASE WHEN a.success = true THEN 1 ELSE 0 END) " +
           "FROM FeishuSyncAuditLog a WHERE a.createdAt >= :since GROUP BY a.operation")
    List<Object[]> getOperationStats(@Param("since") Instant since);
}

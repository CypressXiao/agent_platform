package com.agentplatform.gateway.feishu.repository;

import com.agentplatform.gateway.feishu.model.FeishuSyncTask;
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
public interface FeishuSyncTaskRepository extends JpaRepository<FeishuSyncTask, Long> {

    Optional<FeishuSyncTask> findByTaskId(String taskId);

    List<FeishuSyncTask> findByDocToken(String docToken);

    List<FeishuSyncTask> findByDocTokenAndStatus(String docToken, String status);

    Page<FeishuSyncTask> findByStatus(String status, Pageable pageable);

    @Query("SELECT t FROM FeishuSyncTask t WHERE t.status = 'PENDING' ORDER BY t.createdAt ASC")
    List<FeishuSyncTask> findPendingTasks(Pageable pageable);

    @Query("SELECT t FROM FeishuSyncTask t WHERE t.status = 'FAILED' AND t.retryCount < t.maxRetries " +
           "AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now) ORDER BY t.nextRetryAt ASC")
    List<FeishuSyncTask> findRetryableTasks(@Param("now") Instant now, Pageable pageable);

    @Query("SELECT t FROM FeishuSyncTask t WHERE t.status IN ('PENDING', 'FAILED') AND " +
           "(t.status = 'PENDING' OR (t.retryCount < t.maxRetries AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now))) " +
           "ORDER BY t.createdAt ASC")
    List<FeishuSyncTask> findExecutableTasks(@Param("now") Instant now, Pageable pageable);

    boolean existsByDocTokenAndRevisionAndStatusIn(String docToken, String revision, List<String> statuses);

    @Modifying
    @Query("UPDATE FeishuSyncTask t SET t.status = :status, t.updatedAt = CURRENT_TIMESTAMP WHERE t.taskId = :taskId")
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);

    @Modifying
    @Query("UPDATE FeishuSyncTask t SET t.status = 'RUNNING', t.startedAt = :startedAt, " +
           "t.updatedAt = CURRENT_TIMESTAMP WHERE t.taskId = :taskId AND t.status = 'PENDING'")
    int claimTask(@Param("taskId") String taskId, @Param("startedAt") Instant startedAt);

    @Modifying
    @Query("UPDATE FeishuSyncTask t SET t.status = 'COMPLETED', t.completedAt = :completedAt, " +
           "t.durationMs = :durationMs, t.chunksCreated = :chunksCreated, t.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE t.taskId = :taskId")
    int completeTask(@Param("taskId") String taskId,
                     @Param("completedAt") Instant completedAt,
                     @Param("durationMs") Long durationMs,
                     @Param("chunksCreated") Integer chunksCreated);

    @Modifying
    @Query("UPDATE FeishuSyncTask t SET t.status = 'FAILED', t.retryCount = t.retryCount + 1, " +
           "t.errorCode = :errorCode, t.errorMessage = :errorMessage, t.nextRetryAt = :nextRetryAt, " +
           "t.updatedAt = CURRENT_TIMESTAMP WHERE t.taskId = :taskId")
    int failTask(@Param("taskId") String taskId,
                 @Param("errorCode") String errorCode,
                 @Param("errorMessage") String errorMessage,
                 @Param("nextRetryAt") Instant nextRetryAt);

    long countByStatus(String status);

    @Query("SELECT COUNT(t) FROM FeishuSyncTask t WHERE t.status = 'FAILED' AND t.retryCount >= t.maxRetries")
    long countDeadLetterTasks();

    @Query("SELECT t.docToken, COUNT(t) FROM FeishuSyncTask t WHERE t.status = 'FAILED' " +
           "GROUP BY t.docToken HAVING COUNT(t) > :threshold")
    List<Object[]> findFrequentlyFailingDocuments(@Param("threshold") long threshold);
}

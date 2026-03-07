package com.agentplatform.common.repository;

import com.agentplatform.common.model.ToolJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ToolJobRepository extends JpaRepository<ToolJob, String> {

    List<ToolJob> findByRunId(String runId);

    List<ToolJob> findByConversationId(String conversationId);

    List<ToolJob> findByStatus(String status);

    @Query("SELECT j FROM ToolJob j WHERE j.status IN ('PENDING', 'RUNNING') AND j.timeoutAt < :now")
    List<ToolJob> findTimedOutJobs(@Param("now") Instant now);

    @Query("SELECT j FROM ToolJob j WHERE j.callerTid = :callerTid AND j.createdAt < :before")
    List<ToolJob> findByCallerTidAndCreatedAtBefore(@Param("callerTid") String callerTid, 
                                                      @Param("before") Instant before);

    long countByCallerTid(String callerTid);
}

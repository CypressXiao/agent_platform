package com.agentplatform.gateway.workflow.repository;

import com.agentplatform.gateway.workflow.model.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
    List<WorkflowRun> findByGraphId(String graphId);
    List<WorkflowRun> findByActorTidAndStatus(String actorTid, String status);
    List<WorkflowRun> findByActorTid(String actorTid);
}

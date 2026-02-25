package com.agentplatform.gateway.workflow.repository;

import com.agentplatform.gateway.workflow.model.WorkflowGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowGraphRepository extends JpaRepository<WorkflowGraph, String> {
    List<WorkflowGraph> findByOwnerTidAndStatus(String ownerTid, String status);
    List<WorkflowGraph> findByOwnerTid(String ownerTid);
}

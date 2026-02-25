package com.agentplatform.gateway.planner.repository;

import com.agentplatform.gateway.planner.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<Plan, String> {
    List<Plan> findByActorTid(String actorTid);
    List<Plan> findByActorTidAndStatus(String actorTid, String status);
}

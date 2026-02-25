package com.agentplatform.common.repository;

import com.agentplatform.common.model.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, String> {
    List<Policy> findByStatusOrderByPriorityDesc(String status);
}

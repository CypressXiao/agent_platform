package com.agentplatform.gateway.planner;

import com.agentplatform.gateway.planner.model.Plan;
import com.agentplatform.gateway.planner.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/planner")
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PlannerAdminController {

    private final PlanRepository planRepo;

    @GetMapping("/plans")
    public ResponseEntity<List<Plan>> listPlans(@RequestParam(required = false) String actorTid) {
        if (actorTid != null) return ResponseEntity.ok(planRepo.findByActorTid(actorTid));
        return ResponseEntity.ok(planRepo.findAll());
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<Plan> getPlan(@PathVariable String id) {
        return planRepo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}

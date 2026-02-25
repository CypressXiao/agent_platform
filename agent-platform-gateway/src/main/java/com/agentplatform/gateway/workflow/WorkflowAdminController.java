package com.agentplatform.gateway.workflow;

import com.agentplatform.gateway.workflow.model.WorkflowGraph;
import com.agentplatform.gateway.workflow.model.WorkflowRun;
import com.agentplatform.gateway.workflow.repository.WorkflowGraphRepository;
import com.agentplatform.gateway.workflow.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow")
@ConditionalOnProperty(name = "agent-platform.workflow.enabled", havingValue = "true")
@RequiredArgsConstructor
public class WorkflowAdminController {

    private final WorkflowGraphRepository graphRepo;
    private final WorkflowRunRepository runRepo;

    @PostMapping("/graphs")
    public ResponseEntity<WorkflowGraph> createGraph(@RequestBody WorkflowGraph graph) {
        if (graph.getGraphId() == null) graph.setGraphId(UUID.randomUUID().toString());
        graph.setStatus("draft");
        return ResponseEntity.status(HttpStatus.CREATED).body(graphRepo.save(graph));
    }

    @GetMapping("/graphs")
    public ResponseEntity<List<WorkflowGraph>> listGraphs(@RequestParam String ownerTid) {
        return ResponseEntity.ok(graphRepo.findByOwnerTid(ownerTid));
    }

    @GetMapping("/graphs/{id}")
    public ResponseEntity<WorkflowGraph> getGraph(@PathVariable String id) {
        return graphRepo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/graphs/{id}")
    public ResponseEntity<WorkflowGraph> updateGraph(@PathVariable String id, @RequestBody WorkflowGraph update) {
        return graphRepo.findById(id)
            .map(existing -> {
                if (update.getName() != null) existing.setName(update.getName());
                if (update.getDescription() != null) existing.setDescription(update.getDescription());
                if (update.getDefinition() != null) existing.setDefinition(update.getDefinition());
                existing.setUpdatedAt(Instant.now());
                return ResponseEntity.ok(graphRepo.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/graphs/{id}/publish")
    public ResponseEntity<WorkflowGraph> publishGraph(@PathVariable String id) {
        return graphRepo.findById(id)
            .map(graph -> {
                graph.setStatus("published");
                graph.setUpdatedAt(Instant.now());
                return ResponseEntity.ok(graphRepo.save(graph));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/runs")
    public ResponseEntity<List<WorkflowRun>> listRuns(@RequestParam(required = false) String actorTid,
                                                       @RequestParam(required = false) String graphId) {
        if (graphId != null) return ResponseEntity.ok(runRepo.findByGraphId(graphId));
        if (actorTid != null) return ResponseEntity.ok(runRepo.findByActorTid(actorTid));
        return ResponseEntity.ok(runRepo.findAll());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<WorkflowRun> getRun(@PathVariable String id) {
        return runRepo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}

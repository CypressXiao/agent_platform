package com.agentplatform.gateway.memory;

import com.agentplatform.gateway.memory.model.MemoryNamespace;
import com.agentplatform.gateway.memory.repository.EntityMemoryRepository;
import com.agentplatform.gateway.memory.repository.MemoryEntryRepository;
import com.agentplatform.gateway.memory.repository.MemoryNamespaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/memory")
@ConditionalOnProperty(name = "agent-platform.memory.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MemoryAdminController {

    private final MemoryNamespaceRepository nsRepo;
    private final MemoryEntryRepository entryRepo;
    private final EntityMemoryRepository entityRepo;
    private final MemoryService memoryService;

    @PostMapping("/namespaces")
    public ResponseEntity<MemoryNamespace> createNamespace(@RequestBody MemoryNamespace ns) {
        if (ns.getNamespaceId() == null) ns.setNamespaceId(UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(nsRepo.save(ns));
    }

    @GetMapping("/namespaces")
    public ResponseEntity<List<MemoryNamespace>> listNamespaces(@RequestParam String ownerTid) {
        return ResponseEntity.ok(nsRepo.findByOwnerTid(ownerTid));
    }

    @DeleteMapping("/namespaces/{id}")
    public ResponseEntity<Void> deleteNamespace(@PathVariable String id) {
        nsRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(@RequestParam String ownerTid) {
        List<MemoryNamespace> namespaces = nsRepo.findByOwnerTid(ownerTid);
        long totalEntries = 0;
        for (MemoryNamespace ns : namespaces) {
            totalEntries += entryRepo.countByOwnerTidAndNamespace(ownerTid, ns.getName());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespaces", namespaces.size());
        result.put("total_long_term_entries", totalEntries);
        result.put("mem0_enabled", memoryService.isMem0Enabled());
        result.put("mem0_healthy", memoryService.isMem0Healthy());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memory_enabled", true);
        result.put("mem0_enabled", memoryService.isMem0Enabled());
        result.put("mem0_healthy", memoryService.isMem0Healthy());
        result.put("short_term_store", "redis");
        result.put("long_term_store", memoryService.isMem0Enabled() ? "mem0+milvus" : "postgresql");
        result.put("entity_store", "postgresql");
        return ResponseEntity.ok(result);
    }
}

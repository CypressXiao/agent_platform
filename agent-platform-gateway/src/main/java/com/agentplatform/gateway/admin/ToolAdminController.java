package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.repository.ToolRepository;
import com.agentplatform.gateway.mcp.ToolVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/tools")
@RequiredArgsConstructor
public class ToolAdminController {

    private final ToolRepository toolRepo;
    private final ToolVersionService toolVersionService;

    @GetMapping
    public ResponseEntity<List<Tool>> listTools(
            @RequestParam(required = false) String ownerTid,
            @RequestParam(required = false) String sourceId) {
        if (ownerTid != null) {
            return ResponseEntity.ok(toolRepo.findByOwnerTidAndStatus(ownerTid, "active"));
        }
        if (sourceId != null) {
            return ResponseEntity.ok(toolRepo.findBySourceIdAndStatus(sourceId, "active"));
        }
        return ResponseEntity.ok(toolRepo.findAll());
    }

    @GetMapping("/{toolId}")
    public ResponseEntity<Tool> getTool(@PathVariable String toolId) {
        return toolRepo.findById(toolId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{toolId}/disable")
    public ResponseEntity<Tool> disableTool(@PathVariable String toolId) {
        return toolRepo.findById(toolId)
            .map(tool -> {
                tool.setStatus("disabled");
                tool.setUpdatedAt(Instant.now());
                Tool saved = toolRepo.save(tool);
                // 递增版本号并通知所有连接的客户端
                toolVersionService.incrementVersion();
                return ResponseEntity.ok(saved);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{toolId}/enable")
    public ResponseEntity<Tool> enableTool(@PathVariable String toolId) {
        return toolRepo.findById(toolId)
            .map(tool -> {
                tool.setStatus("active");
                tool.setUpdatedAt(Instant.now());
                Tool saved = toolRepo.save(tool);
                // 递增版本号并通知所有连接的客户端
                toolVersionService.incrementVersion();
                return ResponseEntity.ok(saved);
            })
            .orElse(ResponseEntity.notFound().build());
    }
}

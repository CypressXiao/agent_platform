package com.agentplatform.gateway.admin;

import com.agentplatform.common.dto.RegisterMcpServerRequest;
import com.agentplatform.common.dto.RegisterRestApiRequest;
import com.agentplatform.common.model.UpstreamServer;
import com.agentplatform.gateway.mcp.registry.UpstreamServerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/servers")
@RequiredArgsConstructor
public class ServerAdminController {

    private final UpstreamServerService serverService;

    @PostMapping("/mcp")
    public ResponseEntity<UpstreamServer> registerMcpServer(@Valid @RequestBody RegisterMcpServerRequest request) {
        UpstreamServer server = serverService.registerMcpServer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(server);
    }

    @PostMapping("/rest")
    public ResponseEntity<UpstreamServer> registerRestApi(@Valid @RequestBody RegisterRestApiRequest request) {
        UpstreamServer server = serverService.registerRestApi(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(server);
    }

    @GetMapping
    public ResponseEntity<List<UpstreamServer>> listServers(@RequestParam String ownerTid) {
        return ResponseEntity.ok(serverService.listByTenant(ownerTid));
    }

    @GetMapping("/{serverId}")
    public ResponseEntity<UpstreamServer> getServer(@PathVariable String serverId) {
        return serverService.findById(serverId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{serverId}")
    public ResponseEntity<Void> unregisterServer(@PathVariable String serverId) {
        serverService.unregister(serverId);
        return ResponseEntity.noContent().build();
    }
}

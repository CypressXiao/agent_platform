package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.AuditLog;
import com.agentplatform.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AuditAdminController {

    private final AuditLogRepository auditLogRepo;

    @GetMapping
    public ResponseEntity<Page<AuditLog>> queryAuditLogs(
            @RequestParam(required = false) String actorTid,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));

        if (traceId != null) {
            List<AuditLog> logs = auditLogRepo.findByTraceId(traceId);
            return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(logs, pageRequest, logs.size()));
        }

        if (actorTid != null && from != null && to != null) {
            return ResponseEntity.ok(auditLogRepo.findByTidAndTimeRange(actorTid, from, to, pageRequest));
        }

        if (actorTid != null) {
            return ResponseEntity.ok(auditLogRepo.findByActorTid(actorTid, pageRequest));
        }

        return ResponseEntity.ok(auditLogRepo.findAll(pageRequest));
    }
}

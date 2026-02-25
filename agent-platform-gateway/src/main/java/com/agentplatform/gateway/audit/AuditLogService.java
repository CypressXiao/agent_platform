package com.agentplatform.gateway.audit;

import com.agentplatform.common.model.AuditLog;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.repository.AuditLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Audit logging service. Logs all tool calls asynchronously.
 * Also records metrics for observability.
 */
@Service
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepo;
    private final Tracer tracer;
    private final Counter toolCallCounter;
    private final Counter toolCallErrorCounter;
    private final Timer toolCallTimer;

    public AuditLogService(AuditLogRepository auditLogRepo,
                           Tracer tracer,
                           MeterRegistry meterRegistry) {
        this.auditLogRepo = auditLogRepo;
        this.tracer = tracer;
        this.toolCallCounter = Counter.builder("mcp.tool.calls.total")
            .description("Total tool calls")
            .register(meterRegistry);
        this.toolCallErrorCounter = Counter.builder("mcp.tool.calls.errors")
            .description("Total tool call errors")
            .register(meterRegistry);
        this.toolCallTimer = Timer.builder("mcp.tool.calls.duration")
            .description("Tool call duration")
            .register(meterRegistry);
    }

    @Async
    public void logSuccess(CallerIdentity identity, Tool tool, String grantId, long latencyMs) {
        logSuccess(identity, tool, grantId, latencyMs, null, null);
    }

    @Async
    public void logSuccess(CallerIdentity identity, Tool tool, String grantId, long latencyMs,
                           Map<String, Object> requestArgs, Object responseResult) {
        toolCallCounter.increment();
        toolCallTimer.record(latencyMs, TimeUnit.MILLISECONDS);

        AuditLog entry = AuditLog.builder()
            .logId(UUID.randomUUID().toString())
            .timestamp(Instant.now())
            .traceId(currentTraceId())
            .callerId(identity.getClientId())
            .actorTid(identity.getTenantId())
            .ownerTid(tool.getOwnerTid())
            .toolId(tool.getToolId())
            .toolName(tool.getToolName())
            .sourceId(tool.getSourceId())
            .grantId(grantId)
            .action("tools/call")
            .resultCode("SUCCESS")
            .latencyMs(latencyMs)
            .requestDigest(computeDigest(requestArgs))
            .responseDigest(computeDigest(responseResult))
            .metadata(Map.of("source_type", tool.getSourceType()))
            .build();

        try {
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }

    @Async
    public void logFailure(CallerIdentity identity, String toolName, String grantId,
                           String errorCode, long latencyMs) {
        logFailure(identity, toolName, grantId, errorCode, latencyMs, null);
    }

    @Async
    public void logFailure(CallerIdentity identity, String toolName, String grantId,
                           String errorCode, long latencyMs, Map<String, Object> requestArgs) {
        toolCallCounter.increment();
        toolCallErrorCounter.increment();
        toolCallTimer.record(latencyMs, TimeUnit.MILLISECONDS);

        AuditLog entry = AuditLog.builder()
            .logId(UUID.randomUUID().toString())
            .timestamp(Instant.now())
            .traceId(currentTraceId())
            .callerId(identity.getClientId())
            .actorTid(identity.getTenantId())
            .toolName(toolName)
            .grantId(grantId)
            .action("tools/call")
            .resultCode(errorCode)
            .latencyMs(latencyMs)
            .requestDigest(computeDigest(requestArgs))
            .build();

        try {
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }

    @Async
    public void log(CallerIdentity identity, String action, String toolName,
                    String resultCode, Map<String, Object> metadata) {
        AuditLog entry = AuditLog.builder()
            .logId(UUID.randomUUID().toString())
            .timestamp(Instant.now())
            .traceId(currentTraceId())
            .callerId(identity.getClientId())
            .actorTid(identity.getTenantId())
            .toolName(toolName)
            .action(action)
            .resultCode(resultCode)
            .metadata(metadata)
            .build();

        try {
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.error("Failed to save audit log: {}", e.getMessage());
        }
    }

    private String currentTraceId() {
        if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
            return tracer.currentSpan().context().traceId();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 计算请求/响应内容的 SHA-256 摘要，用于合规审计（不存储原始数据）。
     */
    private String computeDigest(Object data) {
        if (data == null) {
            return null;
        }
        try {
            String json = data.toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.debug("Failed to compute digest: {}", e.getMessage());
            return null;
        }
    }
}

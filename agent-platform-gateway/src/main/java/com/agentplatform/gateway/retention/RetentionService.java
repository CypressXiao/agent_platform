package com.agentplatform.gateway.retention;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.repository.AuditLogRepository;
import com.agentplatform.gateway.job.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 数据保留服务
 * 负责执行保留策略、清理过期数据、归档导出
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RetentionService {

    private final RetentionPolicyRepository policyRepo;
    private final AuditLogRepository auditLogRepo;
    private final StringRedisTemplate redisTemplate;
    private final JobService jobService;

    /**
     * 创建保留策略
     */
    public RetentionPolicy createPolicy(CallerIdentity identity, String name, String dataType,
                                         Integer retentionDays, Boolean archiveEnabled,
                                         String archiveTarget, String archiveFormat) {
        RetentionPolicy policy = RetentionPolicy.builder()
            .policyId(UUID.randomUUID().toString())
            .tenantId(identity.getTenantId())
            .name(name)
            .dataType(dataType)
            .retentionDays(retentionDays != null ? retentionDays : 90)
            .archiveEnabled(archiveEnabled != null ? archiveEnabled : false)
            .archiveTarget(archiveTarget)
            .archiveFormat(archiveFormat != null ? archiveFormat : "json")
            .status("active")
            .build();

        return policyRepo.save(policy);
    }

    /**
     * 列出租户的保留策略
     */
    public List<RetentionPolicy> listPolicies(CallerIdentity identity) {
        return policyRepo.findByTenantIdAndStatus(identity.getTenantId(), "active");
    }

    /**
     * 更新保留策略
     */
    public RetentionPolicy updatePolicy(String policyId, Integer retentionDays,
                                         Boolean archiveEnabled, String archiveTarget) {
        RetentionPolicy policy = policyRepo.findById(policyId)
            .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + policyId));

        if (retentionDays != null) {
            policy.setRetentionDays(retentionDays);
        }
        if (archiveEnabled != null) {
            policy.setArchiveEnabled(archiveEnabled);
        }
        if (archiveTarget != null) {
            policy.setArchiveTarget(archiveTarget);
        }
        policy.setUpdatedAt(Instant.now());

        return policyRepo.save(policy);
    }

    /**
     * 禁用保留策略
     */
    public void disablePolicy(String policyId) {
        RetentionPolicy policy = policyRepo.findById(policyId)
            .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + policyId));
        policy.setStatus("disabled");
        policyRepo.save(policy);
    }

    /**
     * 定时执行保留策略（每天凌晨 2 点）
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void executeRetentionPolicies() {
        log.info("Starting retention policy execution...");

        List<RetentionPolicy> policies = policyRepo.findByStatus("active");
        int totalCleaned = 0;

        for (RetentionPolicy policy : policies) {
            try {
                int cleaned = executePolicy(policy);
                totalCleaned += cleaned;

                policy.setLastExecutedAt(Instant.now());
                policyRepo.save(policy);

                log.info("Executed retention policy: policyId={}, dataType={}, cleaned={}",
                    policy.getPolicyId(), policy.getDataType(), cleaned);

            } catch (Exception e) {
                log.error("Failed to execute retention policy: policyId={}, error={}",
                    policy.getPolicyId(), e.getMessage());
            }
        }

        log.info("Retention policy execution completed. Total cleaned: {}", totalCleaned);
    }

    /**
     * 手动执行单个策略
     */
    @Transactional
    public int executePolicy(RetentionPolicy policy) {
        Instant cutoffTime = Instant.now().minus(policy.getRetentionDays(), ChronoUnit.DAYS);

        return switch (policy.getDataType().toUpperCase()) {
            case "AUDIT" -> cleanAuditLogs(policy.getTenantId(), cutoffTime, policy);
            case "EVENT" -> cleanEvents(policy.getTenantId(), cutoffTime);
            case "MEMORY" -> cleanMemory(policy.getTenantId(), cutoffTime);
            case "USAGE" -> cleanUsageRecords(policy.getTenantId(), cutoffTime);
            case "JOB" -> cleanJobs(policy.getTenantId(), cutoffTime);
            default -> {
                log.warn("Unknown data type: {}", policy.getDataType());
                yield 0;
            }
        };
    }

    /**
     * 清理审计日志
     */
    private int cleanAuditLogs(String tenantId, Instant cutoffTime, RetentionPolicy policy) {
        // 如果启用归档，先导出
        if (policy.getArchiveEnabled() != null && policy.getArchiveEnabled()) {
            archiveAuditLogs(tenantId, cutoffTime, policy);
        }

        // 删除过期数据
        int deleted = auditLogRepo.deleteByActorTidAndTimestampBefore(tenantId, cutoffTime);
        log.debug("Cleaned {} audit logs for tenant {}", deleted, tenantId);
        return deleted;
    }

    /**
     * 归档审计日志
     */
    private void archiveAuditLogs(String tenantId, Instant cutoffTime, RetentionPolicy policy) {
        // TODO: 实现归档逻辑（导出到 S3/OSS）
        log.info("Archiving audit logs for tenant {} to {}", tenantId, policy.getArchiveTarget());
    }

    /**
     * 清理事件数据（Redis）
     */
    private int cleanEvents(String tenantId, Instant cutoffTime) {
        String pattern = "event:run:*";
        Set<String> keys = redisTemplate.keys(pattern);
        int deleted = 0;

        if (keys != null) {
            for (String key : keys) {
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl <= 0) {
                    redisTemplate.delete(key);
                    deleted++;
                }
            }
        }

        log.debug("Cleaned {} event keys", deleted);
        return deleted;
    }

    /**
     * 清理 Memory 数据
     */
    private int cleanMemory(String tenantId, Instant cutoffTime) {
        // Memory 清理由 MemoryService 的定时任务处理
        // 这里只清理 Redis 中的短期记忆
        String pattern = "memory:short:" + tenantId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        int deleted = 0;

        if (keys != null) {
            for (String key : keys) {
                Long ttl = redisTemplate.getExpire(key);
                if (ttl != null && ttl <= 0) {
                    redisTemplate.delete(key);
                    deleted++;
                }
            }
        }

        log.debug("Cleaned {} memory keys for tenant {}", deleted, tenantId);
        return deleted;
    }

    /**
     * 清理用量记录
     */
    private int cleanUsageRecords(String tenantId, Instant cutoffTime) {
        // TODO: 实现 LlmUsageRecord 清理
        log.debug("Usage record cleanup not yet implemented");
        return 0;
    }

    /**
     * 清理 ToolJob 数据
     */
    private int cleanJobs(String tenantId, Instant cutoffTime) {
        jobService.cleanupOldJobs(tenantId, cutoffTime);
        long count = jobService.countJobsByTenant(tenantId);
        log.debug("Cleaned jobs for tenant {}, remaining: {}", tenantId, count);
        return 0; // JobService.cleanupOldJobs 内部已删除，这里返回 0
    }

    /**
     * 获取数据统计
     */
    public Map<String, Object> getDataStats(String tenantId) {
        Map<String, Object> stats = new HashMap<>();

        // 审计日志数量
        long auditCount = auditLogRepo.countByActorTid(tenantId);
        stats.put("auditLogCount", auditCount);

        // 事件数量（Redis）
        String eventPattern = "event:run:*";
        Set<String> eventKeys = redisTemplate.keys(eventPattern);
        stats.put("eventCount", eventKeys != null ? eventKeys.size() : 0);

        // Memory 数量
        String memoryPattern = "memory:*:" + tenantId + ":*";
        Set<String> memoryKeys = redisTemplate.keys(memoryPattern);
        stats.put("memoryCount", memoryKeys != null ? memoryKeys.size() : 0);

        return stats;
    }
}

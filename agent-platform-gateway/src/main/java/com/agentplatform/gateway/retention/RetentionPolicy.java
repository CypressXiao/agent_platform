package com.agentplatform.gateway.retention;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;

/**
 * 数据保留策略
 * 支持按租户/类型配置 TTL 与归档导出
 */
@Entity
@Table(name = "retention_policy")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetentionPolicy {

    @Id
    @Column(name = "policy_id", length = 64)
    private String policyId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /**
     * 数据类型：audit / vector / memory / usage / event
     */
    @Column(name = "data_type", nullable = false, length = 64)
    private String dataType;

    /**
     * 保留天数
     */
    @Column(name = "retention_days")
    @Builder.Default
    private Integer retentionDays = 90;

    /**
     * 是否启用归档
     */
    @Column(name = "archive_enabled")
    @Builder.Default
    private Boolean archiveEnabled = false;

    /**
     * 归档目标（如 S3 bucket）
     */
    @Column(name = "archive_target", length = 512)
    private String archiveTarget;

    /**
     * 归档格式：json / parquet / csv
     */
    @Column(name = "archive_format", length = 32)
    @Builder.Default
    private String archiveFormat = "json";

    /**
     * 额外配置
     */
    @Type(JsonType.class)
    @Column(name = "config", columnDefinition = "json")
    private Map<String, Object> config;

    @Column(name = "status", length = 32)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * 上次执行时间
     */
    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    public enum DataType {
        AUDIT,
        VECTOR,
        MEMORY,
        USAGE,
        EVENT
    }
}

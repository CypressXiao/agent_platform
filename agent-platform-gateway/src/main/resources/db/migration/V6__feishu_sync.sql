-- V6: Feishu Document Sync Schema — MySQL 8.0+
-- Registry for tracking Feishu documents and Sync Task queue

-- Feishu Document Registry: tracks all registered documents for sync
CREATE TABLE IF NOT EXISTS feishu_document_registry (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    doc_token       VARCHAR(64)  NOT NULL COMMENT '飞书文档唯一标识',
    doc_type        VARCHAR(32)  NOT NULL DEFAULT 'docx' COMMENT '文档类型: docx/sheet/wiki/folder',
    source_type     VARCHAR(32)  NOT NULL DEFAULT 'SINGLE' COMMENT '来源类型: SINGLE/WIKI/FOLDER/WEBHOOK',
    title           VARCHAR(512) COMMENT '文档标题',
    url             VARCHAR(1024) COMMENT '飞书文档链接',
    
    -- Profile & Collection mapping
    profile         VARCHAR(64)  NOT NULL DEFAULT 'knowledge' COMMENT 'Chunk profile: sop/knowledge',
    collection      VARCHAR(255) COMMENT 'Milvus collection 名称',
    tenant          VARCHAR(64)  NOT NULL COMMENT '租户标识',
    scene           VARCHAR(128) COMMENT '场景标识，用于 collection 细分',
    
    -- Revision tracking
    last_revision   VARCHAR(64)  COMMENT '最近一次成功同步的 revision ID',
    last_hash       VARCHAR(64)  COMMENT '内容 hash，用于 diff 检测',
    last_sync_at    DATETIME(6)  COMMENT '最近一次成功同步时间',
    
    -- Status & Priority
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DELETED/PERMISSION_DENIED/DISABLED',
    priority        INT          NOT NULL DEFAULT 0 COMMENT '同步优先级，数值越大优先级越高',
    
    -- Parent reference (for Wiki/Folder hierarchy)
    parent_token    VARCHAR(64)  COMMENT '父节点 token（Wiki 空间或目录）',
    space_id        VARCHAR(64)  COMMENT '所属空间 ID',
    
    -- Metadata
    metadata        JSON         COMMENT '额外元数据: 标签、目录路径、提交人等',
    created_by      VARCHAR(64)  COMMENT '创建人',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    
    UNIQUE KEY uk_doc_token (doc_token),
    INDEX idx_tenant_scene (tenant, scene),
    INDEX idx_status (status),
    INDEX idx_profile (profile),
    INDEX idx_space (space_id),
    INDEX idx_parent (parent_token),
    INDEX idx_last_sync (last_sync_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='飞书文档注册表，记录所有需要同步的文档';

-- Feishu Sync Task: task queue for document synchronization
CREATE TABLE IF NOT EXISTS feishu_sync_task (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64)  NOT NULL COMMENT '任务唯一标识',
    doc_token       VARCHAR(64)  NOT NULL COMMENT '飞书文档 token',
    revision        VARCHAR(64)  COMMENT '目标同步的 revision',
    
    -- Trigger info
    trigger_type    VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED' COMMENT '触发类型: SCHEDULED/MANUAL/WEBHOOK/INIT',
    triggered_by    VARCHAR(64)  COMMENT '触发人/系统',
    
    -- Task status
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    max_retries     INT          NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    next_retry_at   DATETIME(6)  COMMENT '下次重试时间（指数退避）',
    
    -- Execution info
    started_at      DATETIME(6)  COMMENT '开始执行时间',
    completed_at    DATETIME(6)  COMMENT '完成时间',
    duration_ms     BIGINT       COMMENT '执行耗时（毫秒）',
    
    -- Result
    error_code      VARCHAR(64)  COMMENT '错误码',
    error_message   TEXT         COMMENT '错误信息',
    chunks_created  INT          COMMENT '创建的 chunk 数量',
    
    -- Metadata
    metadata        JSON         COMMENT '额外信息',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    
    UNIQUE KEY uk_task_id (task_id),
    INDEX idx_doc_token (doc_token),
    INDEX idx_status (status),
    INDEX idx_trigger_type (trigger_type),
    INDEX idx_next_retry (next_retry_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='飞书文档同步任务队列';

-- Feishu Space Registry: tracks Wiki spaces and folders for batch sync
CREATE TABLE IF NOT EXISTS feishu_space_registry (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    space_token     VARCHAR(64)  NOT NULL COMMENT '空间/目录 token',
    space_type      VARCHAR(32)  NOT NULL DEFAULT 'WIKI' COMMENT '类型: WIKI/FOLDER',
    name            VARCHAR(512) COMMENT '空间/目录名称',
    
    -- Default settings for documents in this space
    default_profile VARCHAR(64)  NOT NULL DEFAULT 'knowledge' COMMENT '默认 profile',
    tenant          VARCHAR(64)  NOT NULL COMMENT '租户标识',
    scene           VARCHAR(128) COMMENT '默认场景标识',
    
    -- Sync settings
    auto_discover   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否自动发现新文档',
    sync_enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用同步',
    last_scan_at    DATETIME(6)  COMMENT '最近一次扫描时间',
    
    -- Status
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED/PERMISSION_DENIED',
    
    -- Metadata
    metadata        JSON         COMMENT '额外元数据',
    created_by      VARCHAR(64)  COMMENT '创建人',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    
    UNIQUE KEY uk_space_token (space_token),
    INDEX idx_tenant (tenant),
    INDEX idx_status (status),
    INDEX idx_sync_enabled (sync_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='飞书空间/目录注册表，用于批量同步';

-- Feishu Sync Audit Log: audit trail for sync operations
CREATE TABLE IF NOT EXISTS feishu_sync_audit_log (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    doc_token       VARCHAR(64)  NOT NULL COMMENT '文档 token',
    task_id         VARCHAR(64)  COMMENT '关联的任务 ID',
    
    -- Operation info
    operation       VARCHAR(32)  NOT NULL COMMENT '操作类型: SYNC/DELETE/REGISTER/UNREGISTER/PERMISSION_CHANGE',
    trigger_type    VARCHAR(32)  COMMENT '触发类型',
    triggered_by    VARCHAR(64)  COMMENT '触发人/系统',
    
    -- Before/After state
    old_revision    VARCHAR(64)  COMMENT '同步前 revision',
    new_revision    VARCHAR(64)  COMMENT '同步后 revision',
    old_status      VARCHAR(32)  COMMENT '操作前状态',
    new_status      VARCHAR(32)  COMMENT '操作后状态',
    
    -- Result
    success         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否成功',
    error_message   TEXT         COMMENT '错误信息',
    duration_ms     BIGINT       COMMENT '耗时（毫秒）',
    
    -- Context
    collection      VARCHAR(255) COMMENT '目标 collection',
    chunks_affected INT          COMMENT '影响的 chunk 数量',
    metadata        JSON         COMMENT '额外上下文',
    
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    
    INDEX idx_doc_token (doc_token),
    INDEX idx_task_id (task_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at),
    INDEX idx_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='飞书同步审计日志';

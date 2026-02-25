-- V1: Initial schema for Agent Platform Gateway (MCP 中台 v1) — MySQL 8.0+

-- Tenant table
CREATE TABLE IF NOT EXISTS tenant (
    tid         VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'active',
    quota_config JSON,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Upstream Server table
CREATE TABLE IF NOT EXISTS upstream_server (
    server_id       VARCHAR(128) PRIMARY KEY,
    server_type     VARCHAR(16)  NOT NULL, -- mcp | rest
    base_url        VARCHAR(512) NOT NULL,
    transport       VARCHAR(32),           -- streamable_http | sse | http
    auth_profile    JSON,
    api_spec        JSON,
    health_endpoint VARCHAR(512),
    owner_tid       VARCHAR(64)  NOT NULL,
    health_status   VARCHAR(32)  NOT NULL DEFAULT 'unknown',
    last_health_check DATETIME(6),
    tags            JSON,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_upstream_owner FOREIGN KEY (owner_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_upstream_server_owner ON upstream_server(owner_tid);
CREATE INDEX idx_upstream_server_type  ON upstream_server(server_type);

-- Tool table
CREATE TABLE IF NOT EXISTS tool (
    tool_id           VARCHAR(256) PRIMARY KEY,
    tool_name         VARCHAR(128) NOT NULL,
    description       TEXT,
    source_type       VARCHAR(32)  NOT NULL, -- builtin | upstream_mcp | upstream_rest
    source_id         VARCHAR(128) NOT NULL,
    owner_tid         VARCHAR(64)  NOT NULL,
    required_scopes   JSON,
    input_schema      JSON,
    execution_mapping JSON,
    response_mapping  JSON,
    priority          INT          DEFAULT 0,
    idempotent        TINYINT(1)   DEFAULT 0,
    timeout_ms        INT          DEFAULT 30000,
    rate_limit        INT          DEFAULT 100,
    status            VARCHAR(32)  NOT NULL DEFAULT 'active',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_tool_owner     ON tool(owner_tid, status);
CREATE INDEX idx_tool_source    ON tool(source_id, status);
CREATE INDEX idx_tool_name      ON tool(tool_name);

-- Grant table (cross-tenant sharing)
CREATE TABLE IF NOT EXISTS grant_record (
    grant_id     VARCHAR(64)  PRIMARY KEY,
    grantor_tid  VARCHAR(64)  NOT NULL,
    grantee_tid  VARCHAR(64)  NOT NULL,
    tools        JSON,
    scopes       JSON,
    `constraints` JSON,
    expires_at   DATETIME(6),
    status       VARCHAR(32)  NOT NULL DEFAULT 'active',
    revoked_at   DATETIME(6),
    revoke_reason VARCHAR(512),
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_grant_grantor FOREIGN KEY (grantor_tid) REFERENCES tenant(tid),
    CONSTRAINT fk_grant_grantee FOREIGN KEY (grantee_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_grant_grantor  ON grant_record(grantor_tid, status);
CREATE INDEX idx_grant_grantee  ON grant_record(grantee_tid, status);

-- Audit Log table
CREATE TABLE IF NOT EXISTS audit_log (
    log_id          VARCHAR(64)  PRIMARY KEY,
    `timestamp`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    trace_id        VARCHAR(64),
    caller_id       VARCHAR(128),
    actor_tid       VARCHAR(64),
    owner_tid       VARCHAR(64),
    tool_id         VARCHAR(256),
    tool_name       VARCHAR(128),
    source_id       VARCHAR(128),
    grant_id        VARCHAR(64),
    action          VARCHAR(64),
    result_code     VARCHAR(64),
    latency_ms      BIGINT,
    request_digest  TEXT,
    response_digest TEXT,
    metadata        JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_audit_actor    ON audit_log(actor_tid, `timestamp`);
CREATE INDEX idx_audit_trace    ON audit_log(trace_id);
CREATE INDEX idx_audit_tool     ON audit_log(tool_name, `timestamp`);
CREATE INDEX idx_audit_time     ON audit_log(`timestamp`);

-- Policy table
CREATE TABLE IF NOT EXISTS policy (
    policy_id   VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(32)  NOT NULL, -- rbac | abac
    rules       JSON,
    priority    INT          DEFAULT 0,
    status      VARCHAR(32)  NOT NULL DEFAULT 'active',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert system tenant
INSERT IGNORE INTO tenant (tid, name, status) VALUES ('system', 'System', 'active');

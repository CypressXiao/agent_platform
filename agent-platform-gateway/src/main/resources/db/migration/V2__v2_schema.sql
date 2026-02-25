-- V2: Schema for v2 subsystems (Workflow, Planner, Memory, LLM Router) — MySQL 8.0+
-- Vector storage is handled by Milvus, not in MySQL.

-- Workflow Graph template
CREATE TABLE IF NOT EXISTS workflow_graph (
    graph_id    VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    owner_tid   VARCHAR(64)  NOT NULL,
    version     INT          NOT NULL DEFAULT 1,
    definition  JSON         NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'draft', -- draft | published | archived
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_wf_graph_owner FOREIGN KEY (owner_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_wf_graph_owner ON workflow_graph(owner_tid, status);

-- Workflow Run instance
CREATE TABLE IF NOT EXISTS workflow_run (
    run_id          VARCHAR(64)  PRIMARY KEY,
    graph_id        VARCHAR(64)  NOT NULL,
    graph_version   INT          NOT NULL,
    actor_tid       VARCHAR(64)  NOT NULL,
    trace_id        VARCHAR(64),
    input           JSON,
    output          JSON,
    node_executions JSON,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING', -- PENDING | RUNNING | COMPLETED | FAILED | CANCELLED
    started_at      DATETIME(6),
    completed_at    DATETIME(6),
    total_latency_ms BIGINT,
    CONSTRAINT fk_wf_run_graph FOREIGN KEY (graph_id) REFERENCES workflow_graph(graph_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_wf_run_graph ON workflow_run(graph_id);
CREATE INDEX idx_wf_run_actor ON workflow_run(actor_tid, status);

-- Plan
CREATE TABLE IF NOT EXISTS plan (
    plan_id         VARCHAR(64)  PRIMARY KEY,
    actor_tid       VARCHAR(64)  NOT NULL,
    goal            TEXT         NOT NULL,
    trace_id        VARCHAR(64),
    steps           JSON,
    context         JSON,
    status          VARCHAR(32)  NOT NULL DEFAULT 'CREATED', -- CREATED | EXECUTING | COMPLETED | FAILED
    llm_model       VARCHAR(128),
    llm_tokens_used INT,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at    DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_plan_actor ON plan(actor_tid, status);

-- Memory Entry (long-term fallback when Mem0 is disabled)
CREATE TABLE IF NOT EXISTS memory_entry (
    memory_id       VARCHAR(64)  PRIMARY KEY,
    owner_tid       VARCHAR(64)  NOT NULL,
    agent_id        VARCHAR(128) NOT NULL,
    namespace       VARCHAR(128) NOT NULL DEFAULT 'default',
    content         TEXT         NOT NULL,
    metadata        JSON,
    embedding_model VARCHAR(128),
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      DATETIME(6),
    updated_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_memory_owner FOREIGN KEY (owner_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_memory_owner_ns ON memory_entry(owner_tid, namespace);
CREATE INDEX idx_memory_agent    ON memory_entry(agent_id, namespace);

-- Memory Namespace
CREATE TABLE IF NOT EXISTS memory_namespace (
    namespace_id        VARCHAR(64)  PRIMARY KEY,
    owner_tid           VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    max_entries         INT          DEFAULT 10000,
    max_size_bytes      BIGINT       DEFAULT 104857600, -- 100MB
    default_ttl_seconds INT          DEFAULT 0,
    embedding_model     VARCHAR(128) DEFAULT 'text-embedding-3-small',
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_mem_ns_owner FOREIGN KEY (owner_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_mem_ns_owner ON memory_namespace(owner_tid);

-- LLM Provider
CREATE TABLE IF NOT EXISTS llm_provider (
    provider_id VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    base_url    VARCHAR(512) NOT NULL,
    api_key_ref VARCHAR(512),
    status      VARCHAR(32)  NOT NULL DEFAULT 'active', -- active | disabled
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- LLM Model Config
CREATE TABLE IF NOT EXISTS llm_model_config (
    model_id                VARCHAR(128) PRIMARY KEY,
    provider_id             VARCHAR(64)  NOT NULL,
    model_name              VARCHAR(128) NOT NULL,
    max_tokens              INT          NOT NULL DEFAULT 4096,
    input_price_per_m_token DECIMAL(10,4),
    output_price_per_m_token DECIMAL(10,4),
    supports_streaming      TINYINT(1)   DEFAULT 1,
    supports_tools          TINYINT(1)   DEFAULT 1,
    fallback_model_id       VARCHAR(128),
    status                  VARCHAR(32)  NOT NULL DEFAULT 'active', -- active | deprecated
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider(provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- LLM Tenant Quota
CREATE TABLE IF NOT EXISTS llm_tenant_quota (
    quota_id            VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    model_id            VARCHAR(128) NOT NULL DEFAULT '*',
    rpm_limit           INT          DEFAULT 60,
    tpm_limit           INT          DEFAULT 100000,
    monthly_token_budget BIGINT      DEFAULT 10000000,
    current_month_usage BIGINT       DEFAULT 0,
    reset_at            DATETIME(6),
    CONSTRAINT fk_quota_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_llm_quota_tenant ON llm_tenant_quota(tenant_id);

-- LLM Usage Record
CREATE TABLE IF NOT EXISTS llm_usage_record (
    record_id         VARCHAR(64)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    model_id          VARCHAR(128) NOT NULL,
    trace_id          VARCHAR(64),
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    cost              DECIMAL(10,6),
    latency_ms        BIGINT,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_usage_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_llm_usage_tenant ON llm_usage_record(tenant_id, created_at);

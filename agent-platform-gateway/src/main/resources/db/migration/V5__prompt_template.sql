-- Prompt Template table
CREATE TABLE IF NOT EXISTS prompt_template (
    template_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    template TEXT NOT NULL,
    variables JSON,
    version INT DEFAULT 1,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_name_version (tenant_id, name, version),
    INDEX idx_tenant_status (tenant_id, status)
);

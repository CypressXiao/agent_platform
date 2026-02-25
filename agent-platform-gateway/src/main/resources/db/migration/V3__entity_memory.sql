-- V3: Entity memory table for structured key-value memory storage — MySQL 8.0+

CREATE TABLE IF NOT EXISTS entity_memory (
    entity_id    VARCHAR(64)  PRIMARY KEY,
    owner_tid    VARCHAR(64)  NOT NULL,
    agent_id     VARCHAR(128) NOT NULL,
    entity_type  VARCHAR(128) NOT NULL,  -- e.g. user_preference, fact, profile, relationship
    entity_key   VARCHAR(256) NOT NULL,  -- e.g. favorite_color, name, location
    entity_value TEXT         NOT NULL,
    metadata     JSON,
    confidence   DOUBLE       DEFAULT 1.0,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_entity_mem (owner_tid, agent_id, entity_type, entity_key),
    CONSTRAINT fk_entity_mem_owner FOREIGN KEY (owner_tid) REFERENCES tenant(tid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_entity_mem_owner_agent ON entity_memory(owner_tid, agent_id);
CREATE INDEX idx_entity_mem_type ON entity_memory(owner_tid, agent_id, entity_type);

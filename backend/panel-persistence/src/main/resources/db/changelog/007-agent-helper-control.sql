ALTER TABLE agent ADD COLUMN helper_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

CREATE TABLE agent_control_operation (
 id UUID PRIMARY KEY,
 agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
 idempotency_key VARCHAR(128) NOT NULL UNIQUE,
 capability VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 detail TEXT,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agent_control_operation_agent_created_idx
 ON agent_control_operation(agent_id, created_at DESC);

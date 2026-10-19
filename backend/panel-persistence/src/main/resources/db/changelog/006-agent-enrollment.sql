ALTER TABLE agent ADD COLUMN cert_fingerprint CHAR(64);

CREATE TABLE agent_enrollment (
 id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 agent_id UUID NOT NULL UNIQUE REFERENCES agent(id) ON DELETE CASCADE,
 token_hash CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL,
 consumed_at TIMESTAMPTZ,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX agent_enrollment_expiry_idx ON agent_enrollment(expires_at);

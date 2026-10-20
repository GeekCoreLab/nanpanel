CREATE TABLE api_key (
 id VARCHAR(36) PRIMARY KEY,
 user_id VARCHAR(36) NOT NULL REFERENCES panel_user(id) ON DELETE CASCADE,
 name VARCHAR(80) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE,
 scopes VARCHAR(256) NOT NULL,
 created_at BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 revoked_at BIGINT,
 last_used_at BIGINT,
 created_ip VARCHAR(64),
 CHECK (expires_at > created_at),
 CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX api_key_user_created_idx ON api_key(user_id, created_at DESC);

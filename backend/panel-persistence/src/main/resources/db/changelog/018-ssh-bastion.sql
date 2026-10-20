CREATE TABLE host_ssh_config (
 host_id UUID PRIMARY KEY REFERENCES host(id) ON DELETE CASCADE,
 address VARCHAR(253) NOT NULL,
 port INTEGER NOT NULL DEFAULT 22 CHECK (port BETWEEN 1 AND 65535),
 username VARCHAR(128) NOT NULL,
 auth_method VARCHAR(16) NOT NULL CHECK (auth_method IN ('PASSWORD', 'PRIVATE_KEY')),
 encrypted_credential BYTEA NOT NULL,
 host_key_fingerprint VARCHAR(80),
 probed_host_key_fingerprint VARCHAR(80),
 probed_at TIMESTAMPTZ,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bastion_session (
 id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id),
 actor_id VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 web_session_id UUID NOT NULL,
 reason VARCHAR(500) NOT NULL,
 client_ip VARCHAR(64),
 target_address VARCHAR(253) NOT NULL,
 target_port INTEGER NOT NULL CHECK (target_port BETWEEN 1 AND 65535),
 target_username VARCHAR(128) NOT NULL,
 target_fingerprint VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'CLOSED', 'FAILED')),
 ticket_hash CHAR(64),
 ticket_expires_at TIMESTAMPTZ,
 ticket_used_at TIMESTAMPTZ,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 duration_ms BIGINT,
 bytes_in BIGINT NOT NULL DEFAULT 0,
 bytes_out BIGINT NOT NULL DEFAULT 0,
 recording_chunks INTEGER NOT NULL DEFAULT 0 CHECK (recording_chunks BETWEEN 0 AND 100000),
 terminal_columns INTEGER NOT NULL DEFAULT 120 CHECK (terminal_columns BETWEEN 20 AND 500),
 terminal_rows INTEGER NOT NULL DEFAULT 36 CHECK (terminal_rows BETWEEN 5 AND 200),
 exit_code INTEGER,
 failure_code VARCHAR(64)
);

CREATE INDEX bastion_session_host_order ON bastion_session(host_id, started_at DESC, id);
CREATE INDEX bastion_session_actor_order ON bastion_session(actor_id, started_at DESC, id);

CREATE TABLE bastion_recording_chunk (
 session_id UUID NOT NULL REFERENCES bastion_session(id) ON DELETE CASCADE,
 sequence BIGINT NOT NULL,
 offset_ms BIGINT NOT NULL CHECK (offset_ms >= 0),
 direction VARCHAR(8) NOT NULL CHECK (direction IN ('INPUT', 'OUTPUT')),
 payload BYTEA NOT NULL,
 PRIMARY KEY (session_id, sequence)
);

CREATE TABLE bastion_command (
 session_id UUID NOT NULL REFERENCES bastion_session(id) ON DELETE CASCADE,
 sequence BIGINT NOT NULL,
 occurred_at TIMESTAMPTZ NOT NULL,
 encrypted_command BYTEA NOT NULL,
 PRIMARY KEY (session_id, sequence)
);

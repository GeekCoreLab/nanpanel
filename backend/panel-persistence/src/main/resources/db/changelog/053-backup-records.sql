CREATE TABLE backup_record (
 record_id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 file_name VARCHAR(96) NOT NULL,
 source VARCHAR(1024) NOT NULL,
 size_bytes BIGINT,
 sha256 CHAR(64),
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
   CHECK (state IN ('DRAFT','APPLIED','FAILED','UNKNOWN')),
 operation_id UUID,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE (host_id, file_name)
);

CREATE INDEX backup_record_host_idx ON backup_record(host_id, created_at DESC);

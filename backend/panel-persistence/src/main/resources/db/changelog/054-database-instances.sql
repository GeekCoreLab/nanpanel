CREATE TABLE database_instance (
 instance_id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 engine VARCHAR(16) NOT NULL,
 engine_version VARCHAR(16) NOT NULL,
 container_name VARCHAR(63) NOT NULL,
 host_port INTEGER NOT NULL,
 data_volume VARCHAR(63) NOT NULL,
 database_name VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
   CHECK (state IN ('DRAFT','APPLIED','FAILED','UNKNOWN')),
 operation_id UUID,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE (host_id, host_port),
 UNIQUE (host_id, container_name)
);

CREATE INDEX database_instance_host_idx ON database_instance(host_id, created_at DESC);

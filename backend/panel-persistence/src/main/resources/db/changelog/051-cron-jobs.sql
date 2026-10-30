CREATE TABLE cron_job (
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 job_id VARCHAR(42) NOT NULL,
 schedule VARCHAR(128) NOT NULL,
 command VARCHAR(900) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
   CHECK (state IN ('DRAFT','APPLIED','DISABLED','FAILED','UNKNOWN')),
 operation_id UUID,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY (host_id, job_id)
);

CREATE INDEX cron_job_state_idx ON cron_job(state, updated_at DESC);

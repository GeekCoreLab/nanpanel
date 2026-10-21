CREATE TABLE k3s_install_plan (
 id UUID PRIMARY KEY,
 target_version VARCHAR(32) NOT NULL,
 server_url VARCHAR(512) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 state VARCHAR(40) NOT NULL CHECK (state IN ('DRAFT','RUNNING','AWAITING_CLUSTER_REGISTRATION','AWAITING_NODE_MAPPING','SUCCEEDED','FAILED','UNKNOWN')),
 active_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (active_ordinal >= 0),
 cluster_id UUID REFERENCES kubernetes_cluster(id),
 encrypted_token BYTEA NOT NULL,
 error_code VARCHAR(64),
 error_message VARCHAR(1000),
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 started_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ
);

CREATE INDEX k3s_install_plan_active ON k3s_install_plan(state, created_at)
 WHERE state IN ('RUNNING','AWAITING_CLUSTER_REGISTRATION','AWAITING_NODE_MAPPING');

CREATE TABLE k3s_install_step (
 plan_id UUID NOT NULL REFERENCES k3s_install_plan(id) ON DELETE CASCADE,
 ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
 host_id UUID NOT NULL REFERENCES host(id),
 role VARCHAR(8) NOT NULL CHECK (role IN ('server','agent')),
 expected_generation BIGINT NOT NULL CHECK (expected_generation >= 1),
 state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','SUBMITTING','RUNNING','VERIFYING','SUCCEEDED','FAILED','UNKNOWN')),
 idempotency_key VARCHAR(128) NOT NULL UNIQUE,
 operation_id UUID REFERENCES operation(id),
 error_code VARCHAR(64),
 error_message VARCHAR(1000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 PRIMARY KEY (plan_id,ordinal),
 UNIQUE (plan_id,host_id)
);

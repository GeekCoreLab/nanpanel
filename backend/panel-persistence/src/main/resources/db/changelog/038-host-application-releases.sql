CREATE TABLE host_application_release_plan (
 id UUID PRIMARY KEY,
 application_name VARCHAR(63) NOT NULL,
 stable_image VARCHAR(512) NOT NULL,
 image VARCHAR(512) NOT NULL,
 container_port INTEGER NOT NULL CHECK (container_port BETWEEN 1 AND 65535),
 server_name VARCHAR(253) NOT NULL,
 listen_port INTEGER NOT NULL CHECK (listen_port BETWEEN 1 AND 65535),
 health_path VARCHAR(256) NOT NULL,
 batch_size INTEGER NOT NULL CHECK (batch_size BETWEEN 1 AND 50),
 active_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (active_ordinal >= 0),
 state VARCHAR(16) NOT NULL CHECK (state IN
   ('DRAFT','RUNNING','PAUSED','ROLLING_BACK','SUCCEEDED','ROLLED_BACK','FAILED','UNKNOWN')),
 reason VARCHAR(500) NOT NULL,
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 error_code VARCHAR(64),
 error_message VARCHAR(1000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 started_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ
);

CREATE TABLE host_application_release_target (
 release_id UUID NOT NULL REFERENCES host_application_release_plan(id) ON DELETE CASCADE,
 ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
 application_name VARCHAR(63) NOT NULL,
 host_id UUID NOT NULL REFERENCES host(id),
 expected_generation BIGINT NOT NULL CHECK (expected_generation >= 1),
 listen_address VARCHAR(64) NOT NULL,
 route_name VARCHAR(63) NOT NULL,
 stable_container_name VARCHAR(63) NOT NULL,
 stable_image VARCHAR(512) NOT NULL,
 stable_port INTEGER NOT NULL CHECK (stable_port BETWEEN 1 AND 65535),
 candidate_container_name VARCHAR(63) NOT NULL,
 candidate_port INTEGER NOT NULL CHECK (candidate_port BETWEEN 1 AND 65535),
 stable_existed BOOLEAN NOT NULL,
 state VARCHAR(40) NOT NULL,
 operation_id UUID REFERENCES operation(id),
 detail VARCHAR(1000),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (release_id, ordinal),
 UNIQUE (release_id, host_id),
 CHECK (stable_port <> candidate_port)
);

CREATE TABLE host_application_deployment (
 host_id UUID NOT NULL REFERENCES host(id),
 application_name VARCHAR(63) NOT NULL,
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 image VARCHAR(512) NOT NULL,
 container_name VARCHAR(63) NOT NULL,
 host_port INTEGER NOT NULL CHECK (host_port BETWEEN 1 AND 65535),
 container_port INTEGER NOT NULL CHECK (container_port BETWEEN 1 AND 65535),
 route_name VARCHAR(63) NOT NULL,
 listen_address VARCHAR(64) NOT NULL,
 listen_port INTEGER NOT NULL CHECK (listen_port BETWEEN 1 AND 65535),
 server_name VARCHAR(253) NOT NULL,
 health_path VARCHAR(256) NOT NULL,
 generation BIGINT NOT NULL CHECK (generation >= 1),
 release_id UUID NOT NULL REFERENCES host_application_release_plan(id),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (host_id, application_name)
);

CREATE INDEX host_application_release_owner_created
 ON host_application_release_plan(requested_by, created_at DESC);
CREATE INDEX host_application_release_state_created
 ON host_application_release_plan(state, created_at);
CREATE INDEX host_application_release_target_host
 ON host_application_release_target(host_id, application_name, state);
CREATE INDEX host_application_deployment_owner
 ON host_application_deployment(requested_by, application_name);

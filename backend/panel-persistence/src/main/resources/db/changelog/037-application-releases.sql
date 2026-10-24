CREATE TABLE application_release_plan (
 id UUID PRIMARY KEY,
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id),
 namespace VARCHAR(63) NOT NULL,
 application_name VARCHAR(63) NOT NULL,
 rollout_name VARCHAR(63) NOT NULL,
 route_name VARCHAR(63) NOT NULL,
 analysis_name VARCHAR(63) NOT NULL,
 stable_service_name VARCHAR(63) NOT NULL,
 canary_service_name VARCHAR(63) NOT NULL,
 gateway_name VARCHAR(63) NOT NULL,
 hostname VARCHAR(253) NOT NULL,
 prometheus_service_name VARCHAR(63) NOT NULL,
 stable_image VARCHAR(512) NOT NULL,
 image VARCHAR(512) NOT NULL,
 container_port INTEGER NOT NULL CHECK (container_port BETWEEN 1 AND 65535),
 replicas INTEGER NOT NULL CHECK (replicas BETWEEN 1 AND 20),
 max_error_rate NUMERIC(6,5) NOT NULL CHECK (max_error_rate > 0 AND max_error_rate <= 0.5),
 reason VARCHAR(500) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT','STARTING','RUNNING','PAUSED','SUCCEEDED','ROLLED_BACK','FAILED','UNKNOWN')),
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 rollout_uid VARCHAR(128),
 route_uid VARCHAR(128),
 observed_stable_weight INTEGER CHECK (observed_stable_weight BETWEEN 0 AND 100),
 observed_canary_weight INTEGER CHECK (observed_canary_weight BETWEEN 0 AND 100),
 rollout_phase VARCHAR(32),
 current_step_message VARCHAR(1000),
 error_code VARCHAR(64),
 error_message VARCHAR(1000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 started_at TIMESTAMPTZ,
 observed_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ
);

CREATE INDEX application_release_owner_created ON application_release_plan(requested_by, created_at DESC);
CREATE INDEX application_release_cluster_namespace ON application_release_plan(cluster_id, namespace, created_at DESC);
CREATE UNIQUE INDEX application_release_active ON application_release_plan(cluster_id, namespace, application_name)
WHERE state IN ('DRAFT','STARTING','RUNNING','PAUSED','UNKNOWN');

CREATE TABLE application_release_stage (
 release_id UUID NOT NULL REFERENCES application_release_plan(id) ON DELETE CASCADE,
 ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
 stage VARCHAR(32) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','ACTIVE','OBSERVED','SUCCEEDED','INCONCLUSIVE','FAILED')),
 desired_canary_weight INTEGER CHECK (desired_canary_weight BETWEEN 0 AND 100),
 observed_canary_weight INTEGER CHECK (observed_canary_weight BETWEEN 0 AND 100),
 observed_stable_weight INTEGER CHECK (observed_stable_weight BETWEEN 0 AND 100),
 analysis_run_name VARCHAR(253),
 detail VARCHAR(1000),
 started_at TIMESTAMPTZ,
 finished_at TIMESTAMPTZ,
 PRIMARY KEY (release_id, ordinal)
);

CREATE TABLE application_release_measurement (
 id BIGSERIAL PRIMARY KEY,
 release_id UUID NOT NULL REFERENCES application_release_plan(id) ON DELETE CASCADE,
 analysis_run_name VARCHAR(253) NOT NULL,
 metric_name VARCHAR(128) NOT NULL,
 measurement_ordinal INTEGER NOT NULL CHECK (measurement_ordinal >= 0),
 phase VARCHAR(16) NOT NULL CHECK (phase IN ('RUNNING','SUCCESSFUL','FAILED','INCONCLUSIVE','ERROR')),
 value NUMERIC(18,8),
 detail VARCHAR(1000),
 sampled_at TIMESTAMPTZ NOT NULL,
 UNIQUE (release_id, analysis_run_name, metric_name, measurement_ordinal)
);

CREATE INDEX application_release_measurement_history ON application_release_measurement(release_id, sampled_at DESC);

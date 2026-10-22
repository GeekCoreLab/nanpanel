CREATE TABLE cluster_provider_operation (
 id UUID PRIMARY KEY,
 provider_id VARCHAR(80) NOT NULL,
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id),
 idempotency_key CHAR(64) NOT NULL,
 request_fingerprint CHAR(64) NOT NULL,
 generation BIGINT NOT NULL CHECK (generation >= 0),
 operation VARCHAR(80) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK (state IN ('PLANNED','VALIDATED','DRIFTED','FAILED')),
 plan_configuration TEXT NOT NULL,
 detail VARCHAR(512) NOT NULL,
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE (provider_id, idempotency_key)
);

CREATE INDEX cluster_provider_operation_cluster_idx
 ON cluster_provider_operation(cluster_id, updated_at);

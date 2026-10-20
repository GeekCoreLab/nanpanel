CREATE TABLE network_provider_operation (
 id VARCHAR(36) PRIMARY KEY,
 provider_id VARCHAR(80) NOT NULL,
 idempotency_key CHAR(64) NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 state VARCHAR(16) NOT NULL CHECK (state IN (
   'PLANNED', 'VALIDATED', 'APPLYING', 'APPLIED', 'VERIFIED',
   'DRIFTED', 'COMPENSATING', 'COMPENSATED', 'UNKNOWN', 'FAILED'
 )),
 plan_configuration TEXT NOT NULL,
 operation_id VARCHAR(36),
 detail VARCHAR(512) NOT NULL,
 requested_by VARCHAR(36) NOT NULL,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE (provider_id, idempotency_key)
);
CREATE INDEX network_provider_operation_state_idx
 ON network_provider_operation(state, updated_at);

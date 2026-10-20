CREATE TABLE kubernetes_service_probe (
 probe_key CHAR(64) PRIMARY KEY,
 source_id VARCHAR(80) NOT NULL,
 service_name VARCHAR(63) NOT NULL,
 endpoint_address VARCHAR(253) NOT NULL,
 port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
 protocol VARCHAR(8) NOT NULL CHECK (protocol = 'TCP'),
 outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'SKIPPED')),
 inventory_observed_at TIMESTAMPTZ NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX kubernetes_service_probe_source_time_idx
 ON kubernetes_service_probe(source_id, observed_at DESC);
CREATE INDEX kubernetes_service_probe_observed_idx
 ON kubernetes_service_probe(observed_at);

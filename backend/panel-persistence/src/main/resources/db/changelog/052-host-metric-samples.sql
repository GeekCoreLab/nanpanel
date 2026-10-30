CREATE TABLE host_metric_sample (
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 recorded_at BIGINT NOT NULL,
 operation_id UUID NOT NULL UNIQUE,
 cpu_total BIGINT NOT NULL,
 cpu_idle_total BIGINT NOT NULL,
 mem_total_kib BIGINT NOT NULL,
 mem_available_kib BIGINT NOT NULL,
 load1 DOUBLE PRECISION NOT NULL,
 uptime_seconds BIGINT NOT NULL,
 PRIMARY KEY (host_id, recorded_at)
);

CREATE INDEX host_metric_sample_recorded_idx ON host_metric_sample(recorded_at DESC);

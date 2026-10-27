CREATE TABLE kubernetes_application_packet_capture (
 capture_id UUID PRIMARY KEY,
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 revision_id UUID NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 link_id UUID NOT NULL,
 capture_point VARCHAR(128),
 operation_id UUID UNIQUE REFERENCES operation(id),
 idempotency_key VARCHAR(128) NOT NULL UNIQUE,
 state VARCHAR(16) NOT NULL CHECK (state IN ('CAPTURING','COMPLETE','FAILED','EXPIRED')),
 pcapng BYTEA,
 packet_count INTEGER,
 captured_bytes INTEGER,
 truncated BOOLEAN NOT NULL DEFAULT FALSE,
 detail TEXT,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 started_at BIGINT NOT NULL,
 completed_at BIGINT,
 expires_at BIGINT NOT NULL
);

CREATE INDEX kubernetes_application_packet_capture_recent
 ON kubernetes_application_packet_capture(application_id, started_at DESC);
CREATE UNIQUE INDEX kubernetes_application_packet_capture_active
 ON kubernetes_application_packet_capture(application_id) WHERE state='CAPTURING';
CREATE INDEX kubernetes_application_packet_capture_expiry
 ON kubernetes_application_packet_capture(expires_at) WHERE pcapng IS NOT NULL;

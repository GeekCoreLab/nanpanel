ALTER TABLE alert_channel ADD COLUMN notify_recovery BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE alert_firing_state ADD COLUMN rule_name VARCHAR(120);
ALTER TABLE alert_firing_state ADD COLUMN severity VARCHAR(16);
ALTER TABLE alert_firing_state ADD COLUMN condition_kind VARCHAR(32);
ALTER TABLE alert_firing_state ADD COLUMN detail VARCHAR(500);

CREATE TABLE alert_event (
 event_id UUID PRIMARY KEY,
 dedupe_key VARCHAR(200) NOT NULL,
 rule_id UUID,
 rule_name VARCHAR(120) NOT NULL,
 channel_id UUID,
 channel_name VARCHAR(120),
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('FIRING','RECOVERED')),
 severity VARCHAR(16) NOT NULL,
 condition_kind VARCHAR(32) NOT NULL,
 hostname VARCHAR(255),
 detail VARCHAR(500),
 status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED')),
 error VARCHAR(300),
 created_at BIGINT NOT NULL
);

CREATE INDEX alert_event_created_idx ON alert_event(created_at DESC);
CREATE INDEX alert_event_kind_idx ON alert_event(kind, created_at DESC);

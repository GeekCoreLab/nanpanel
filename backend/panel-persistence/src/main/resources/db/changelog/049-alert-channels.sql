CREATE TABLE alert_channel (
 channel_id UUID PRIMARY KEY,
 name VARCHAR(120) NOT NULL UNIQUE,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('WEBHOOK')),
 url VARCHAR(500) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 last_status VARCHAR(16),
 last_at BIGINT,
 last_error VARCHAR(300),
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);

ALTER TABLE alert_rule ADD COLUMN channel_id UUID
 REFERENCES alert_channel(channel_id) ON DELETE SET NULL;

CREATE TABLE alert_firing_state (
 dedupe_key VARCHAR(200) PRIMARY KEY,
 rule_id UUID NOT NULL,
 channel_id UUID REFERENCES alert_channel(channel_id) ON DELETE CASCADE,
 hostname VARCHAR(255),
 since BIGINT NOT NULL,
 last_sent_at BIGINT
);

CREATE INDEX alert_firing_state_rule_idx ON alert_firing_state(rule_id);

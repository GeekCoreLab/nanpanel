CREATE TABLE alert_rule (
 rule_id UUID PRIMARY KEY,
 name VARCHAR(120) NOT NULL,
 condition_kind VARCHAR(32) NOT NULL
   CHECK (condition_kind IN ('HOST_NOT_ONLINE','HELPER_UNAVAILABLE','SERVICE_INACTIVE')),
 host_id UUID REFERENCES host(id) ON DELETE CASCADE,
 unit_name VARCHAR(255),
 severity VARCHAR(16) NOT NULL CHECK (severity IN ('WARNING','CRITICAL')),
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL
);

CREATE INDEX alert_rule_enabled_idx ON alert_rule(enabled, created_at DESC);

CREATE TABLE audit_event (
  id VARCHAR(36) PRIMARY KEY,
  occurred_at BIGINT NOT NULL,
  actor_id VARCHAR(36) REFERENCES panel_user(id),
  action VARCHAR(100) NOT NULL,
  resource_type VARCHAR(80) NOT NULL,
  resource_id VARCHAR(200),
  outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
  trace_id VARCHAR(36) NOT NULL,
  client_ip VARCHAR(64),
  summary VARCHAR(500) NOT NULL
);
CREATE INDEX audit_event_order ON audit_event(id);
CREATE INDEX audit_event_time ON audit_event(occurred_at, id);
CREATE INDEX audit_event_actor ON audit_event(actor_id, id);

ALTER TABLE audit_event DROP CONSTRAINT audit_event_outcome_check;
ALTER TABLE audit_event ADD CONSTRAINT audit_event_outcome_check
  CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED', 'ACCEPTED', 'UNKNOWN'));

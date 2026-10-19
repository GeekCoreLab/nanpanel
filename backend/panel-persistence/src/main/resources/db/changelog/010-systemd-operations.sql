ALTER TABLE system_service_observation
 ADD COLUMN restart_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE operation ADD COLUMN kind VARCHAR(64);
ALTER TABLE operation ADD COLUMN host_id UUID REFERENCES host(id);
ALTER TABLE operation ADD COLUMN agent_id UUID REFERENCES agent(id);
ALTER TABLE operation ADD COLUMN service_id UUID REFERENCES system_service_observation(id);
ALTER TABLE operation ADD COLUMN requested_by VARCHAR(36) REFERENCES panel_user(id);
ALTER TABLE operation ADD COLUMN reason VARCHAR(500);
ALTER TABLE operation ADD COLUMN expected_generation BIGINT;
ALTER TABLE operation ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE operation ADD COLUMN result_json TEXT;
ALTER TABLE operation ADD COLUMN error_json TEXT;
ALTER TABLE operation ADD COLUMN request_hash CHAR(64);

CREATE INDEX operation_host_created_idx ON operation(host_id, created_at);
CREATE INDEX operation_service_status_idx ON operation(service_id, status);

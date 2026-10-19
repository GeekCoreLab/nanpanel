CREATE TABLE system_service_observation (
 id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 unit_name VARCHAR(255) NOT NULL,
 display_name VARCHAR(255) NOT NULL,
 active_state VARCHAR(16) NOT NULL,
 sub_state VARCHAR(64) NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE (host_id, unit_name)
);

CREATE INDEX system_service_observation_host_idx
 ON system_service_observation(host_id, unit_name);

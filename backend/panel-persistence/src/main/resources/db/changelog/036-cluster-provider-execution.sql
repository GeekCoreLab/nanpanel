ALTER TABLE cluster_provider_operation
 DROP CONSTRAINT cluster_provider_operation_state_check;

ALTER TABLE cluster_provider_operation
 ADD CONSTRAINT cluster_provider_operation_state_check
 CHECK (state IN ('PLANNED','VALIDATED','DRIFTED','EXECUTING',
                  'AWAITING_NODE_REGISTRATION','SUCCEEDED','FAILED','UNKNOWN'));

ALTER TABLE cluster_provider_operation
 ALTER COLUMN state TYPE VARCHAR(40);

ALTER TABLE cluster_provider_operation
 ADD COLUMN host_operation_id UUID REFERENCES operation(id);

CREATE UNIQUE INDEX cluster_provider_host_operation_unique
 ON cluster_provider_operation(host_operation_id)
;

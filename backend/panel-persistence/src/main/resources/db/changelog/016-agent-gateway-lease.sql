ALTER TABLE agent_gateway_fencing ADD COLUMN owner_id TEXT;
ALTER TABLE agent_gateway_fencing ADD COLUMN lease_until TIMESTAMPTZ;

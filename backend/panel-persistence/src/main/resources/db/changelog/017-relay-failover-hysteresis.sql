ALTER TABLE agent_relay_status
  ADD COLUMN unhealthy_count INTEGER NOT NULL DEFAULT 0 CHECK (unhealthy_count >= 0);

ALTER TABLE agent_relay_status
  ADD COLUMN last_counted_at TIMESTAMPTZ;

UPDATE agent_relay_status SET last_counted_at=last_seen WHERE last_counted_at IS NULL;

ALTER TABLE agent_relay_status
  ALTER COLUMN last_counted_at SET NOT NULL;

ALTER TABLE dns_managed_record
  ADD COLUMN content_changed_at TIMESTAMPTZ;

ALTER TABLE dns_managed_record
  ADD COLUMN switch_count INTEGER NOT NULL DEFAULT 0 CHECK (switch_count >= 0);

UPDATE dns_managed_record SET content_changed_at=updated_at WHERE content_changed_at IS NULL;

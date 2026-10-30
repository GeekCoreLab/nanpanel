ALTER TABLE backup_record ADD COLUMN instance_id UUID;

CREATE INDEX backup_record_instance_idx ON backup_record(instance_id, created_at DESC);

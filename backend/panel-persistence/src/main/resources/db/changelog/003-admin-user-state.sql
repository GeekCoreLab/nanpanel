ALTER TABLE panel_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE panel_user ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE panel_user ADD CONSTRAINT panel_user_status_check CHECK (status IN ('ACTIVE', 'DISABLED'));
CREATE INDEX panel_user_created ON panel_user(created_at, id);

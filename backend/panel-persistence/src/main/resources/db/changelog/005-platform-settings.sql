CREATE TABLE platform_settings (
 id INTEGER PRIMARY KEY CHECK (id = 1),
 site_name VARCHAR(100) NOT NULL,
 default_timezone VARCHAR(100) NOT NULL,
 host_offline_after_seconds INTEGER NOT NULL CHECK (host_offline_after_seconds BETWEEN 30 AND 600),
 operation_retention_days INTEGER NOT NULL CHECK (operation_retention_days BETWEEN 7 AND 365),
 updated_at BIGINT NOT NULL,
 updated_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 version BIGINT NOT NULL DEFAULT 1
);

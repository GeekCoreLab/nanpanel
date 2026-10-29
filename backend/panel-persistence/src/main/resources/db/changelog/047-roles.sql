CREATE TABLE panel_role (
 role_id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(120) NOT NULL,
 builtin BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL
);

CREATE TABLE panel_role_permission (
 role_id VARCHAR(64) NOT NULL REFERENCES panel_role(role_id) ON DELETE CASCADE,
 permission VARCHAR(120) NOT NULL,
 PRIMARY KEY (role_id, permission)
);

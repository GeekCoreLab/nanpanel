CREATE TABLE auth_bootstrap_lock (id INTEGER PRIMARY KEY);
INSERT INTO auth_bootstrap_lock VALUES (1);
CREATE TABLE panel_user (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(100) NOT NULL,
 password_hash VARCHAR(256) NOT NULL, must_change BOOLEAN NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, last_login BIGINT
);
CREATE TABLE web_session (
 id VARCHAR(36) PRIMARY KEY, token_hash VARCHAR(64) NOT NULL UNIQUE, user_id VARCHAR(36) REFERENCES panel_user(id),
 csrf VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, authenticated_at BIGINT NOT NULL, last_seen BIGINT NOT NULL,
 idle_expires BIGINT NOT NULL, absolute_expires BIGINT NOT NULL, user_agent VARCHAR(512)
);
CREATE INDEX web_session_user ON web_session(user_id);

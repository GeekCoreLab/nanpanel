CREATE TABLE reverse_proxy_site (
 site_id UUID PRIMARY KEY,
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 domain VARCHAR(253) NOT NULL,
 listen_address VARCHAR(45) NOT NULL,
 listen_port INTEGER NOT NULL CHECK (listen_port BETWEEN 1 AND 65535),
 upstream_port INTEGER NOT NULL CHECK (upstream_port BETWEEN 1 AND 65535),
 health_path VARCHAR(256) NOT NULL DEFAULT '/',
 tls_name VARCHAR(32),
 state VARCHAR(16) NOT NULL CONSTRAINT reverse_proxy_site_state_check CHECK (state IN ('DRAFT','APPLIED','DISABLED','FAILED')),
 operation_id UUID REFERENCES operation(id),
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX reverse_proxy_site_endpoint_unique
 ON reverse_proxy_site(host_id, domain, listen_port);
CREATE INDEX reverse_proxy_site_host_idx
 ON reverse_proxy_site(host_id, created_at DESC);

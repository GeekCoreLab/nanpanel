CREATE TABLE cluster_provider_credential (
 id UUID PRIMARY KEY,
 provider_id VARCHAR(80) NOT NULL,
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id),
 purpose VARCHAR(48) NOT NULL,
 encrypted_material BYTEA NOT NULL,
 expires_at BIGINT,
 revoked_at BIGINT,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL
);

CREATE INDEX cluster_provider_credential_scope_idx
 ON cluster_provider_credential(provider_id, cluster_id, created_at);

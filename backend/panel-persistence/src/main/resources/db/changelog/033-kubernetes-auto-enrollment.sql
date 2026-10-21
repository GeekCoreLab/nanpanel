ALTER TABLE kubernetes_cluster ALTER COLUMN encrypted_kubeconfig DROP NOT NULL;
ALTER TABLE kubernetes_cluster ALTER COLUMN imported_by DROP NOT NULL;
ALTER TABLE kubernetes_cluster ADD COLUMN credential_source VARCHAR(16) NOT NULL DEFAULT 'KUBECONFIG';
ALTER TABLE kubernetes_cluster ADD CONSTRAINT kubernetes_cluster_credential_source_check
 CHECK (credential_source IN ('KUBECONFIG','IN_CLUSTER'));
ALTER TABLE kubernetes_cluster ADD COLUMN cluster_fingerprint VARCHAR(128);
ALTER TABLE kubernetes_cluster ADD COLUMN discovered_host_id UUID REFERENCES host(id);
CREATE UNIQUE INDEX kubernetes_cluster_fingerprint_unique
 ON kubernetes_cluster(cluster_fingerprint) WHERE cluster_fingerprint IS NOT NULL;
CREATE UNIQUE INDEX kubernetes_cluster_discovered_host_unique
 ON kubernetes_cluster(discovered_host_id) WHERE discovered_host_id IS NOT NULL;

CREATE TABLE kubernetes_cluster (
 id UUID PRIMARY KEY,
 name VARCHAR(80) NOT NULL UNIQUE,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('K3S','KUBEEDGE','KUBERNETES')),
 endpoint VARCHAR(512) NOT NULL,
 context_name VARCHAR(253) NOT NULL,
 encrypted_kubeconfig VARBINARY,
 health_status VARCHAR(16) NOT NULL CHECK (health_status IN ('UNKNOWN','HEALTHY','DEGRADED','UNAVAILABLE')),
 cloudcore_status VARCHAR(16) NOT NULL DEFAULT 'NOT_OBSERVED' CHECK (cloudcore_status IN ('HEALTHY','DEGRADED','UNAVAILABLE','NOT_OBSERVED','UNKNOWN')),
 cloudcore_observed_at TIMESTAMP WITH TIME ZONE,
 observed_at TIMESTAMP WITH TIME ZONE,
 last_error_code VARCHAR(64),
 imported_by VARCHAR(36) REFERENCES panel_user(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 credential_source VARCHAR(16) NOT NULL DEFAULT 'KUBECONFIG' CHECK (credential_source IN ('KUBECONFIG','IN_CLUSTER')),
 cluster_fingerprint VARCHAR(128),
 discovered_host_id UUID REFERENCES host(id)
);
CREATE UNIQUE INDEX kubernetes_cluster_fingerprint_unique
 ON kubernetes_cluster(cluster_fingerprint);
CREATE UNIQUE INDEX kubernetes_cluster_discovered_host_unique
 ON kubernetes_cluster(discovered_host_id);
CREATE TABLE kubernetes_cluster_node (
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id) ON DELETE CASCADE,
 node_uid VARCHAR(128) NOT NULL,
 node_name VARCHAR(253) NOT NULL,
 roles VARCHAR(512) NOT NULL,
 kubeedge_edge BOOLEAN NOT NULL DEFAULT FALSE,
 ready BOOLEAN NOT NULL DEFAULT FALSE,
 kubelet_version VARCHAR(128),
 internal_ip VARCHAR(64),
 container_owner VARCHAR(16) NOT NULL CHECK (container_owner IN ('KUBERNETES','KUBEEDGE')),
 host_id UUID REFERENCES host(id),
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY (cluster_id,node_uid)
);
CREATE UNIQUE INDEX kubernetes_cluster_node_host_unique ON kubernetes_cluster_node(host_id);

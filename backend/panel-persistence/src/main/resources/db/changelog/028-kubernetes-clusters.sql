CREATE TABLE kubernetes_cluster (
 id UUID PRIMARY KEY,
 name VARCHAR(80) NOT NULL UNIQUE,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('K3S','KUBEEDGE','KUBERNETES')),
 endpoint VARCHAR(512) NOT NULL,
 context_name VARCHAR(253) NOT NULL,
 encrypted_kubeconfig BYTEA NOT NULL,
 health_status VARCHAR(16) NOT NULL CHECK (health_status IN ('UNKNOWN','HEALTHY','DEGRADED','UNAVAILABLE')),
 cloudcore_status VARCHAR(16) NOT NULL DEFAULT 'NOT_OBSERVED' CHECK (cloudcore_status IN ('HEALTHY','DEGRADED','UNAVAILABLE','NOT_OBSERVED','UNKNOWN')),
 cloudcore_observed_at TIMESTAMPTZ,
 observed_at TIMESTAMPTZ,
 last_error_code VARCHAR(64),
 imported_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
 observed_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY (cluster_id,node_uid)
);

CREATE UNIQUE INDEX kubernetes_cluster_node_host_unique
 ON kubernetes_cluster_node(host_id) WHERE host_id IS NOT NULL;
CREATE INDEX kubernetes_cluster_node_name_order
 ON kubernetes_cluster_node(cluster_id,node_name,node_uid);

CREATE TABLE kubernetes_managed_deployment (
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id) ON DELETE CASCADE,
 namespace VARCHAR(63) NOT NULL,
 deployment_name VARCHAR(63) NOT NULL,
 deployment_uid VARCHAR(128) NOT NULL,
 ownership_token VARCHAR(36) NOT NULL UNIQUE,
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (cluster_id,namespace,deployment_name)
);

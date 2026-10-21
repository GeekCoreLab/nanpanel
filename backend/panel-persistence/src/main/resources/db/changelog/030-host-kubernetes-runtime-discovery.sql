CREATE TABLE host_kubernetes_runtime_discovery (
 host_id UUID PRIMARY KEY REFERENCES host(id) ON DELETE CASCADE,
 detected BOOLEAN NOT NULL,
 kubelet_running BOOLEAN NOT NULL,
 api_server_local BOOLEAN NOT NULL,
 runtime_kind VARCHAR(16) NOT NULL CHECK (runtime_kind IN ('NONE','K3S','KUBERNETES')),
 observed_at TIMESTAMPTZ NOT NULL,
 CHECK ((detected AND kubelet_running AND runtime_kind IN ('K3S','KUBERNETES'))
     OR (NOT detected AND NOT kubelet_running AND runtime_kind='NONE'))
);
